package com.viscosiety.api;

import java.io.IOException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import jakarta.json.Json;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.frankframework.lifecycle.servlets.IAuthenticator;
import org.frankframework.lifecycle.servlets.NoOpAuthenticator;
import org.frankframework.lifecycle.servlets.ServletConfiguration;
import org.frankframework.util.StringUtil;
import org.springframework.core.env.Environment;

import org.frankframework.visco.security.AgentApiServlet;

import com.viscosiety.tunnel.TunnelOnlyApiFilter;

/** Public-port policy for the single ApiListenerServlet. The tunnel has a different servlet. */
public final class GroupedApi implements Filter {
    private static final String PREFIX = "servlet.ApiListenerServlet.";
    private final Map<String, List<String>> roles;

    GroupedApi(Map<String, List<String>> roles) {
        this.roles = Map.copyOf(roles);
    }

    public static boolean install(ServletConfiguration servlet, Environment env, ServletContext context,
            Map<String, IAuthenticator> authenticators, Consumer<List<String>> declareRoles) {
        if (env.containsProperty("visco.api.groups") || System.getenv("visco.api.groups") != null) {
            throw new IllegalArgumentException("visco.api.groups was removed. Set " + PREFIX
                    + "authenticator=GROUPED and migrate the group list to " + PREFIX + "authenticationGroups");
        }
        List<String> names = StringUtil.split(env.getProperty(PREFIX + "authenticationGroups"));
        if (!"GROUPED".equals(servlet.getAuthenticatorName()) || authenticators.containsKey("GROUPED")) {
            if (!names.isEmpty()) {
                throw new IllegalArgumentException(PREFIX + "authenticationGroups requires authenticator GROUPED");
            }
            return false;
        }
        if (!"GROUPED".equals(env.getProperty(PREFIX + "authenticator"))
                || names.isEmpty() || !servlet.getSecurityRoles().isEmpty()
                || !List.of("/api/*").equals(servlet.getUrlMapping())) {
            throw new IllegalArgumentException("GROUPED requires authenticationGroups, /api/*, no servlet-wide roles");
        }
        Map<String, List<String>> roles = new LinkedHashMap<>();
        for (String name : names) {
            if (!name.matches("[a-z][a-z0-9-]{1,29}") || "oauth2".equals(name) || "api-service".equals(name) || roles.containsKey(name)) {
                throw new IllegalArgumentException("Invalid or duplicate API group: " + name);
            }
            String prefix = PREFIX + "group." + name + ".";
            String alias = env.getRequiredProperty(prefix + "authenticator");
            List<String> required = List.copyOf(StringUtil.split(env.getProperty(prefix + "securityRoles")));
            IAuthenticator authenticator = authenticators.get(alias);
            if ("NONE".equals(alias) && authenticator instanceof NoOpAuthenticator && required.isEmpty()) {
                roles.put(name, List.of());
                continue;
            }
            if (authenticator == null || authenticator instanceof NoOpAuthenticator || required.isEmpty()) {
                throw new IllegalArgumentException(prefix + "requires a protecting authenticator and securityRoles, or NONE without roles");
            }
            ServletConfiguration scope = new ServletConfiguration();
            scope.setUrlMapping("/api/" + name + "/*");
            scope.setSecurityRoles(required.toArray(String[]::new));
            declareRoles.accept(required);
            authenticator.registerServlet(scope);
            roles.put(name, required);
        }
        GroupedApi policy = new GroupedApi(roles);
        var filter = context.addFilter("groupedApi", policy);
        filter.setAsyncSupported(true);
        filter.addMappingForServletNames(EnumSet.allOf(DispatcherType.class), false, servlet.getName());
        context.setAttribute(GroupedApi.class.getName(), policy);
        var metadata = context.addServlet("groupedApiConsole", policy.consoleMetadata());
        if (!metadata.addMapping("/iaf/api/visco-api-groups").isEmpty()) {
            throw new IllegalStateException("Console group metadata mapping already in use");
        }
        return true;
    }

    /** Roles for an already-authorized tenant gateway dispatch to a grouped listener. */
    public static List<String> forwardedRoles(ServletContext context, String path) {
        Object installed = context.getAttribute(GroupedApi.class.getName());
        if (installed instanceof GroupedApi grouped && path != null) {
            return grouped.roles.getOrDefault(ApiGroupOpenApi.firstSegment(path), List.of());
        }
        return List.of();
    }

    private HttpServlet consoleMetadata() {
        return new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                response.setContentType("application/json");
                var groups = Json.createArrayBuilder();
                roles.keySet().stream().sorted().forEach(groups::add);
                response.getWriter().write(groups.build().toString());
            }
        };
    }

    @Override
    public void doFilter(ServletRequest input, ServletResponse output, FilterChain chain) throws IOException, ServletException {
        var request = (HttpServletRequest) input;
        var response = (HttpServletResponse) output;
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            if (AgentApiServlet.isAuthorizedForward(request)) {
                chain.doFilter(request, response);
            } else {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            }
            return;
        }
        String path = request.getPathInfo();
        String group = ApiGroupOpenApi.firstSegment(path);
        List<String> required = group == null ? null : roles.get(group);
        if (required == null || (!required.isEmpty() && (request.getUserPrincipal() == null
                || required.stream().noneMatch(request::isUserInRole)))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        if (("/" + group + "/openapi.json").equalsIgnoreCase(TunnelOnlyApiFilter.cleanUri(path))) {
            ApiGroupOpenApi.write(response, group, request);
            return;
        }
        var check = GroupListenerPolicy.check(group, request.getMethod(), path);
        switch (check.action()) {
            case NOT_FOUND -> response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            case OPENAPI_NOT_FOUND -> response.sendError(HttpServletResponse.SC_NOT_FOUND, TunnelOnlyApiFilter.OPENAPI_NOT_FOUND_MESSAGE);
            case PATTERN_OPENAPI -> ApiGroupOpenApi.writePattern(response, check.config(), request);
            case PASS -> chain.doFilter(request, check.hiddenMethods().isEmpty() ? response
                    : new TunnelOnlyApiFilter.TunnelMethodHidingResponse(response, check.hiddenMethods()));
        }
    }
}
