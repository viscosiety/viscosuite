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

package com.viscosiety.viscostore.operation;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.util.ParametersUtil;
import org.hl7.fhir.instance.model.api.IBaseParameters;

import java.util.Arrays;
import java.util.List;

/**
 * Refuses {@code ValueSet/$expand} with a text {@code filter} while Hibernate Search is off.
 *
 * <p>Without Hibernate Search HAPI expands from the database and ignores the text filter without a
 * word: {@code $expand?filter=app} answers every code, as if it had filtered. A client cannot tell, so
 * the request is answered with 400 instead. Registered only when Hibernate Search is off
 * ({@link com.viscosiety.viscostore.config.ExpandTextFilterGuardConfig}).
 *
 * <p>Runs at SERVER_INCOMING_REQUEST_PRE_HANDLED, where HAPI has already parsed an operation's
 * Parameters body into the request's resource, so a GET query parameter and a POST body are both seen.
 */
@Interceptor
public class ExpandTextFilterGuard {

    public static final String MESSAGE =
        "ValueSet/$expand with a text filter needs Hibernate Search, which is off on this server";

    private static final String EXPAND = "$expand";
    private static final String FILTER = "filter";

    @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED)
    public void refuseTextFilter(RequestDetails theRequest) {
        if (!EXPAND.equals(theRequest.getOperation())) {
            return;
        }
        if (anyText(queryValues(theRequest)) || anyText(bodyValues(theRequest))) {
            throw new InvalidRequestException(MESSAGE
                + ": expand without the filter and filter the codes yourself.");
        }
    }

    private static List<String> queryValues(RequestDetails theRequest) {
        String[] values = theRequest.getParameters() == null ? null : theRequest.getParameters().get(FILTER);
        return values == null ? List.of() : Arrays.asList(values);
    }

    private static List<String> bodyValues(RequestDetails theRequest) {
        if (theRequest.getResource() instanceof IBaseParameters parameters) {
            return ParametersUtil.getNamedParameterValuesAsString(theRequest.getFhirContext(), parameters, FILTER);
        }
        return List.of();
    }

    private static boolean anyText(List<String> values) {
        return values.stream().anyMatch(value -> value != null && !value.isBlank());
    }
}
