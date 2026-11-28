package com.viscosiety.api;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import org.frankframework.lifecycle.servlets.ServletConfiguration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GroupedApiTest {
    @Test
    void correctAuthenticationWithWrongRoleIsForbidden() throws Exception {
        var filter = new GroupedApi(Map.of("hospital", List.of("Clinician"), "partner", List.of("Partner")));
        var request = new MockHttpServletRequest("GET", "/api/hospital/status");
        request.setPathInfo("/hospital/status");
        request.setUserPrincipal(() -> "partner-user");
        request.addUserRole("Partner");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(403, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void declaringGroupsWithoutGroupedModeIsRejected() {
        var env = new MockEnvironment().withProperty("servlet.ApiListenerServlet.authenticationGroups", "public");
        var config = mock(ServletConfiguration.class);
        when(config.getAuthenticatorName()).thenReturn("NONE");
        assertThrows(IllegalArgumentException.class, () -> GroupedApi.install(config, env, null, Map.of(), roles -> {}));
    }

    @Test
    void groupedModeRequiresAnExplicitGroupList() {
        var config = mock(ServletConfiguration.class);
        when(config.getAuthenticatorName()).thenReturn("GROUPED");
        assertThrows(IllegalArgumentException.class, () -> GroupedApi.install(config, new MockEnvironment(), null, Map.of(), roles -> {}));
    }
}
