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

import java.io.IOException;
import java.io.Serial;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.viscosiety.pack.PackJson;
import com.viscosiety.pack.PackRegistry;

import org.frankframework.lifecycle.IbisInitializer;

/**
 * Bearer-JWT-only {@code GET /api-service/pack}: which vertical pack this instance runs, as the
 * JSON {@link PackJson} renders (id, subject identifier, console views, ...). The portal and
 * agents use it to learn what an instance's data is called instead of assuming a health instance.
 *
 * <p>The descriptor is not secret, but the endpoint is not public either: it sits in the same
 * family as {@link ConfigRefServlet} and answers only callers {@link #rejectUnauthorized} lets
 * through. ViscoFlow reads the same descriptor through its own console-session endpoint.</p>
 */
@IbisInitializer
public class PackServlet extends AbstractBearerServiceServlet {

	@Serial
	private static final long serialVersionUID = 1L;

	/**
	 * Must be {@code servlet.<getName()>.securityRoles}: the Frank!Framework's servlet manager
	 * reads this servlet's own {@code servlet.<name>.authenticator} and {@code .securityRoles}
	 * settings by name, and the fail-closed check in the base class reads the roles from here.
	 */
	static final String SECURITY_ROLES_PROPERTY = "servlet.pack.securityRoles";

	@Override
	public String getName() {
		return "pack";
	}

	@Override
	public String getUrlMapping() {
		return "/api-service/pack";
	}

	@Override
	protected String securityRolesProperty() {
		return SECURITY_ROLES_PROPERTY;
	}

	@Override
	protected String[] elevatedRoles() {
		// No management-bus call happens here -- the descriptor comes from an in-process registry --
		// so there is nothing to elevate to.
		return new String[0];
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		if (rejectUnauthorized(resp)) {
			return;
		}
		// Rendered before any header is set: if the registry ever throws, the container answers a
		// clean 500 rather than a half-formed 200.
		byte[] body = PackJson.of(PackRegistry.get()).getBytes(StandardCharsets.UTF_8);
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		// An instance's pack is fixed for the life of its image, but a proxy must still not keep an
		// authenticated answer for a caller who is later refused.
		resp.setHeader("Cache-Control", "no-store");
		resp.getOutputStream().write(body);
	}
}
