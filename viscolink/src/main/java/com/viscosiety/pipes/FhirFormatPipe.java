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

package com.viscosiety.pipes;

import java.io.IOException;
import java.util.Locale;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import org.frankframework.configuration.ConfigurationException;
import org.frankframework.core.PipeLineSession;
import org.frankframework.core.PipeRunException;
import org.frankframework.core.PipeRunResult;
import org.frankframework.pipes.FixedForwardPipe;
import org.frankframework.stream.Message;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.jspecify.annotations.NonNull;
import org.springframework.http.MediaType;

/**
 * Converts a FHIR resource between the FHIR serialization formats using the HAPI FHIR
 * parsers, so the output is <em>structurally correct</em> FHIR — unlike generic
 * XML&harr;JSON conversion, which cannot know that FHIR JSON requires arrays
 * ({@code entry}, {@code identifier}, …) even for single elements.
 *
 * <p>The input encoding (XML or JSON) is detected automatically from the content. The
 * target is configured as a FHIR mimetype (or shorthand), so a flow can deliver in
 * whatever format the destination requires, as deploy-time configuration:</p>
 *
 * <pre>{@code
 * <Pipe className="com.viscosiety.pipes.FhirFormatPipe" name="toTargetFormat"
 *       outputFormat="${destination.fhir.mimetype:-application/fhir+json}" fhirVersion="R4"/>
 * }</pre>
 *
 * <p>Supported {@code outputFormat} values: {@code application/fhir+json},
 * {@code application/fhir+xml} (mimetype parameters such as {@code ; fhirVersion=4.0}
 * are tolerated), and the shorthands {@code json} / {@code xml}. Parsing the input also
 * verifies it is well-formed FHIR of the configured version: unparseable input (e.g. an
 * unknown resource type) raises a {@link PipeRunException}, so in a transacted receiver
 * the message retries and parks in the error store.</p>
 */
public class FhirFormatPipe extends FixedForwardPipe {

    private String outputFormat = "application/fhir+json";
    private String fhirVersion = "R4";
    private boolean prettyPrint = false;

    private FhirContext fhirContext;
    private boolean targetJson;
    private String targetMimeType;

    @Override
    public void configure() throws ConfigurationException {
        super.configure();

        fhirContext = switch (fhirVersion.toUpperCase(Locale.ROOT)) {
            case "R4" -> FhirContext.forR4();
            case "R5" -> FhirContext.forR5();
            case "DSTU3" -> FhirContext.forDstu3();
            default -> throw new ConfigurationException(
                    "Unsupported fhirVersion '" + fhirVersion + "'; supported values: R4, R5, DSTU3");
        };

        // mimetype parameters (e.g. "; fhirVersion=4.0") are tolerated
        String format = outputFormat.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        switch (format) {
            case "application/fhir+json", "json" -> {
                targetJson = true;
                targetMimeType = "application/fhir+json";
            }
            case "application/fhir+xml", "xml" -> {
                targetJson = false;
                targetMimeType = "application/fhir+xml";
            }
            default -> throw new ConfigurationException(
                    "Unsupported outputFormat '" + outputFormat
                            + "'; supported: application/fhir+json, application/fhir+xml (or json/xml)");
        }
    }

    @Override
    public @NonNull PipeRunResult doPipe(@NonNull Message message, @NonNull PipeLineSession session)
            throws PipeRunException {

        String input;
        try {
            input = message.asString();
        } catch (IOException e) {
            throw new PipeRunException(this, "Could not read input message", e);
        }
        if (input == null || input.isBlank()) {
            throw new PipeRunException(this, "Got empty input; expected a FHIR resource in XML or JSON");
        }

        boolean inputIsXml = input.stripLeading().startsWith("<");
        IBaseResource resource;
        try {
            IParser inputParser = inputIsXml ? fhirContext.newXmlParser() : fhirContext.newJsonParser();
            resource = inputParser.parseResource(input);
        } catch (Exception e) {
            throw new PipeRunException(this,
                    "FHIR parse failed (" + (inputIsXml ? "XML" : "JSON") + ", " + fhirVersion + "): " + e.getMessage(), e);
        }

        IParser outputParser = targetJson ? fhirContext.newJsonParser() : fhirContext.newXmlParser();
        String output = outputParser.setPrettyPrint(prettyPrint).encodeResourceToString(resource);

        Message result = new Message(output);
        result.getContext().withMimeType(MediaType.parseMediaType(targetMimeType));
        return new PipeRunResult(getSuccessForward(), result);
    }

    /** Target FHIR format: a FHIR mimetype ({@code application/fhir+json} / {@code application/fhir+xml},
     * parameters tolerated) or the shorthand {@code json} / {@code xml}. Default {@code application/fhir+json}. */
    public void setOutputFormat(String outputFormat) {
        this.outputFormat = outputFormat;
    }

    /** FHIR version of the payload: R4 (default), R5, or DSTU3. */
    public void setFhirVersion(String fhirVersion) {
        this.fhirVersion = fhirVersion;
    }

    /** Pretty-print the converted output. Default false. */
    public void setPrettyPrint(boolean prettyPrint) {
        this.prettyPrint = prettyPrint;
    }
}
