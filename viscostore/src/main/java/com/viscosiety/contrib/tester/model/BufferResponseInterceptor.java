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

package com.viscosiety.contrib.tester.model;

import ca.uhn.fhir.jpa.starter.common.FhirServerConfigCommon;
import ca.uhn.fhir.rest.client.api.IClientInterceptor;
import ca.uhn.fhir.rest.client.api.IHttpRequest;
import ca.uhn.fhir.rest.client.api.IHttpResponse;

import java.io.IOException;

public class BufferResponseInterceptor implements IClientInterceptor {

	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(BufferResponseInterceptor.class);


	@Override
	public void interceptRequest(IHttpRequest theRequest) {
		// nothing
	}

	@Override
	public void interceptResponse(IHttpResponse theResponse) throws IOException {
		theResponse.bufferEntity();
	}
}
