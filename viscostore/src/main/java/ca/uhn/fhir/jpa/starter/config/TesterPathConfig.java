package ca.uhn.fhir.jpa.starter.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.io.IOException;

/**
 * With spring.mvc.servlet.path=/tester, the DispatcherServlet only handles /tester/*.
 * HAPI Tester's Thymeleaf templates generate absolute URLs (/resources/**, /about, /resource, etc.)
 * without the /tester prefix (using @{'/'} which resolves to the context path only).
 * This filter redirects those requests to /tester/** so the browser sends a clean request that
 * the DispatcherServlet can handle.
 *
 * A redirect is used instead of a server-side forward because Tomcat's forward merges the original
 * request parameters with the dispatch path's query string, which breaks Spring MVC model binding.
 * The Location header is set as a path-only URL so that the browser resolves it against its current
 * origin, avoiding Docker's internal/external port mismatch that sendRedirect() would cause.
 */
@Configuration
public class TesterPathConfig {

    @Bean
    public FilterRegistrationBean<TesterRedirectFilter> testerRedirectFilter() {
        FilterRegistrationBean<TesterRedirectFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new TesterRedirectFilter());
        bean.addUrlPatterns("/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    static class TesterRedirectFilter implements Filter {

        @Override
        public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest request = (HttpServletRequest) req;

            String contextPath = request.getContextPath();
            String path = request.getRequestURI().substring(contextPath.length());

            // Pass through paths that have their own servlet handlers or are already under /tester
            if (path.startsWith("/tester")
                    || path.startsWith("/fhir")
                    || path.startsWith("/cds-hooks")
                    || path.startsWith("/mcp")
                    || path.startsWith("/sse")
                    || path.equals("/")) {
                chain.doFilter(req, res);
                return;
            }

            // Redirect to /tester/* — path-only Location so the browser resolves it against its
            // own origin, avoiding Docker's internal/external port mismatch.
            String query = request.getQueryString();
            String location = contextPath + "/tester" + path + (query != null ? "?" + query : "");
            HttpServletResponse response = (HttpServletResponse) res;
            response.setStatus(HttpServletResponse.SC_FOUND);
            response.setHeader("Location", location);
        }

        @Override
        public void init(FilterConfig filterConfig) {}

        @Override
        public void destroy() {}
    }
}
