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

package org.frankframework.visco.security;

import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import org.frankframework.lifecycle.IbisInitializer;
import org.frankframework.lifecycle.ServletManager;
import org.frankframework.lifecycle.servlets.ServletConfiguration;
import org.frankframework.util.SpringUtils;

import com.viscosiety.api.ApiGroups;

/** Registers one {@link ApiGroupServlet} per listed group (viscoFoundry spec 2026-11-27 api-endpoint-groups). */
@IbisInitializer
public class ApiGroupServletRegistrar implements InitializingBean {

    private static final Logger LOG = LogManager.getLogger(ApiGroupServletRegistrar.class);

    private final ServletManager servletManager;
    private final Function<ApiGroupServlet, ServletConfiguration> configFor;

    @Autowired
    public ApiGroupServletRegistrar(ServletManager servletManager, ApplicationContext applicationContext) {
        this(servletManager, servlet -> {
            ServletConfiguration config = SpringUtils.createBean(applicationContext);
            config.fromServlet(servlet);
            return config;
        });
    }

    ApiGroupServletRegistrar(ServletManager servletManager, Function<ApiGroupServlet, ServletConfiguration> configFor) {
        this.servletManager = servletManager;
        this.configFor = configFor;
    }

    @Override
    public void afterPropertiesSet() {
        for (String group : ApiGroups.names()) {
            ApiGroupServlet servlet = new ApiGroupServlet(group);
            servletManager.register(configFor.apply(servlet));
            LOG.info("api-groups: group [{}] serves /api/{}/ as servlet [{}]", group, group, servlet.getName());
        }
    }
}
