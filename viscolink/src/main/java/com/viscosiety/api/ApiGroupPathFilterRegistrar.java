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

package com.viscosiety.api;

import java.util.EnumSet;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.web.context.WebApplicationContext;

/**
 * Registers {@link ApiGroupPathFilter} on Frank!Framework's {@code ApiListenerServlet} (by servlet name, so a changed
 * {@code servlet.ApiListenerServlet.urlMapping} is covered; a group servlet has another name) at boot, for the
 * REQUEST dispatch only. Skipped on a
 * configuration reload (the filter from the first boot stays in the already-initialised ServletContext) and never
 * allowed to fail the context refresh; a registration that fails is logged at ERROR, because without the filter
 * group endpoints answer under the default login.
 */
public class ApiGroupPathFilterRegistrar implements InitializingBean, ApplicationContextAware {

    static final String FILTER_NAME = "apiGroupPath";
    /** {@code AbstractHttpServlet.getName()}: the simple class name, which ServletManager registers it under. */
    static final String API_SERVLET_NAME = "ApiListenerServlet";
    private static final Logger log = LogManager.getLogger(ApiGroupPathFilterRegistrar.class);

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterPropertiesSet() {
        if (ApiGroups.names().isEmpty()) {
            log.info("api-groups: no groups; filter not registered");
            return;
        }
        ServletContext servletContext = findServletContext(applicationContext);
        if (servletContext == null) {
            log.error("api-groups: no ServletContext; group paths are NOT locked on /api");
            return;
        }
        if (servletContext.getFilterRegistration(FILTER_NAME) != null) {
            log.info("api-groups: public-side filter already registered (configuration reload); keeping it");
            return;
        }
        try {
            FilterRegistration.Dynamic registration = servletContext.addFilter(FILTER_NAME, new ApiGroupPathFilter());
            if (registration == null) {
                log.info("api-groups: public-side filter already registered; keeping it");
                return;
            }
            registration.setAsyncSupported(true);
            registration.addMappingForServletNames(EnumSet.of(DispatcherType.REQUEST), false, API_SERVLET_NAME);
            log.info("api-groups: group paths answer 404 on the default servlet [{}]", API_SERVLET_NAME);
        } catch (IllegalStateException e) {
            log.error("api-groups: the public-side filter could not be registered; group paths are NOT locked on /api", e);
        }
    }

    private static ServletContext findServletContext(ApplicationContext ctx) {
        if (ctx instanceof WebApplicationContext wac) {
            return wac.getServletContext();
        }
        ApplicationContext parent = ctx == null ? null : ctx.getParent();
        return parent != null ? findServletContext(parent) : null;
    }
}
