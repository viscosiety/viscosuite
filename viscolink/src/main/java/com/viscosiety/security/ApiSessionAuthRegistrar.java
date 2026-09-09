/*
 * Copyright 2026 Viscosiety B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.viscosiety.security;

import jakarta.servlet.ServletContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.web.context.WebApplicationContext;

/**
 * Registers {@link ApiSessionAuthListener} at boot, mirroring {@link ConsoleSecurityRegistrar}:
 * only when the console authenticates through OIDC (there is no session login to bridge
 * otherwise), skipped on a configuration reload (the listener from the first boot survives in the
 * already-initialised ServletContext), and never allowed to fail the context refresh. Opt out with
 * {@code viscolink.api.sessionAuth=false}.
 */
public class ApiSessionAuthRegistrar implements InitializingBean, ApplicationContextAware {

    private static final Logger log = LogManager.getLogger(ApiSessionAuthRegistrar.class);
    private static final String CONSOLE_AUTH_TYPE = "application.security.console.authentication.type";
    private static final String ENABLED_PROPERTY = "viscolink.api.sessionAuth";
    private static final String REGISTERED_ATTRIBUTE = ApiSessionAuthRegistrar.class.getName() + ".registered";

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterPropertiesSet() {
        ServletContext servletContext = findServletContext(applicationContext);
        ApplicationContext parentCtx = applicationContext.getParent();
        if (servletContext == null || parentCtx == null) {
            log.warn("ApiSessionAuthRegistrar: no ServletContext/parent context -- /api keeps Basic-only browser access");
            return;
        }
        if (!Boolean.parseBoolean(parentCtx.getEnvironment().getProperty(ENABLED_PROPERTY, "true"))) {
            log.info("ApiSessionAuthRegistrar: disabled via {}=false", ENABLED_PROPERTY);
            return;
        }
        String type = parentCtx.getEnvironment().getProperty(CONSOLE_AUTH_TYPE);
        if (!"OAUTH2".equalsIgnoreCase(type)) {
            log.info("ApiSessionAuthRegistrar: console authentication is [{}], not OAUTH2 -- nothing to bridge onto /api",
                    type == null ? "unset" : type);
            return;
        }
        if (servletContext.getAttribute(REGISTERED_ATTRIBUTE) != null) {
            log.info("ApiSessionAuthRegistrar: listener already registered (config reload) -- keeping it");
            return;
        }
        try {
            servletContext.addListener(new ApiSessionAuthListener());
            servletContext.setAttribute(REGISTERED_ATTRIBUTE, Boolean.TRUE);
            log.info("ApiSessionAuthRegistrar: same-origin /api requests now accept the console's OIDC session");
        } catch (IllegalStateException e) {
            log.info("ApiSessionAuthRegistrar: ServletContext already initialised -- /api keeps Basic-only browser access ({})",
                    e.getMessage());
        }
    }

    private static ServletContext findServletContext(ApplicationContext ctx) {
        if (ctx instanceof WebApplicationContext wac) {
            return wac.getServletContext();
        }
        ApplicationContext parent = ctx.getParent();
        return parent != null ? findServletContext(parent) : null;
    }
}
