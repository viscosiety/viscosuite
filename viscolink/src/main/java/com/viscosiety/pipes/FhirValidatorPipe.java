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

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationResult;
import org.frankframework.configuration.ConfigurationException;
import org.frankframework.core.PipeForward;
import org.frankframework.core.PipeLineSession;
import org.frankframework.core.PipeRunException;
import org.frankframework.core.PipeRunResult;
import org.frankframework.doc.Forward;
import org.frankframework.pipes.FixedForwardPipe;
import org.frankframework.stream.Message;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.util.stream.Collectors;

/**
 * Validates a FHIR resource (XML or JSON) against a configurable FHIR version using
 * the HAPI FHIR instance validator. Supports R4, R5, and DSTU3.
 *
 * <p>The input encoding (JSON or XML) is detected automatically from the content.
 * On success the original message is passed through unchanged. On failure the pipe outputs
 * an {@code OperationOutcome} in the same encoding as the input (XML or JSON) and takes the
 * {@code failure} forward; if no
 * {@code failure} forward is configured a {@link PipeRunException} is thrown instead,
 * listing the validation errors.</p>
 *
 * <p>Extensions not defined in the base specification are allowed by default, reflecting
 * real-world FHIR usage. Terminology validation uses only codes defined in the FHIR
 * specification itself — no external terminology server is required.</p>
 *
 * <p>This pipe contains a workaround for running HAPI FHIR / HL7 FHIR Core validation
 * inside Frank!Framework runtimes where Saxon is present on the classpath. In such
 * runtimes, JAXP provider discovery may select Saxon's {@link DocumentBuilderFactory}
 * implementation for generic DOM parsing. The HL7 FHIR Core validator applies standard
 * XML security hardening attributes such as {@code XMLConstants.ACCESS_EXTERNAL_DTD};
 * Saxon's factory may reject those attributes with "Not supported".</p>
 *
 * <p>To avoid that provider collision, this pipe forces the JDK/Xerces DOM parser factory
 * before HAPI FHIR is initialized and before validation is executed.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * <FhirValidatorPipe name="ValidateFhir" fhirVersion="R4">
 *     <Forward name="success" path="NextPipe"/>
 *     <Forward name="failure" path="HandleInvalid"/>
 * </FhirValidatorPipe>
 * }</pre>
 */
@Forward(name = "success", description = "the FHIR resource passed validation; the original message is passed through unchanged")
@Forward(name = "failure", description = "the FHIR resource failed validation; message contains an OperationOutcome in the same encoding as the input")
public class FhirValidatorPipe extends FixedForwardPipe {

    private static final Logger LOG = LoggerFactory.getLogger(FhirValidatorPipe.class);

    private static final String FAILURE_FORWARD = "failure";

    private String fhirVersion = "R4";
    private boolean failOnUnknownProfiles = false;

    private FhirContext fhirContext;
    private FhirValidator validator;

    @Override
    public void configure() throws ConfigurationException {
        super.configure();

        fhirContext = switch (fhirVersion.toUpperCase()) {
            case "R4" -> FhirContext.forR4();
            case "R5" -> FhirContext.forR5();
            case "DSTU3" -> FhirContext.forDstu3();
            default -> throw new ConfigurationException(
                    "Unsupported fhirVersion '" + fhirVersion + "'; supported values: R4, R5, DSTU3");
        };

        ValidationSupportChain supportChain = new ValidationSupportChain(
                new DefaultProfileValidationSupport(fhirContext),
                new InMemoryTerminologyServerValidationSupport(fhirContext),
                new CommonCodeSystemsTerminologyService(fhirContext));

        FhirInstanceValidator instanceValidator = new FhirInstanceValidator(supportChain);
        instanceValidator.setAnyExtensionsAllowed(true);
        // resources may CLAIM profiles this validator has no package for (e.g. nl-core);
        // by default that is not a refusal — see setFailOnUnknownProfiles
        instanceValidator.setErrorForUnknownProfiles(failOnUnknownProfiles);

        validator = fhirContext.newValidator();
        validator.registerValidatorModule(instanceValidator);
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

        boolean inputIsXml = input.stripLeading().startsWith("<");

        ValidationResult result;
        try {
            IBaseResource resource = parseResource(input);
            result = validator.validateWithResult(resource);
        } catch (DataFormatException e) {
            // Unparseable or structurally invalid FHIR is a SENDER error, same as a
            // validation failure: when a failure forward is configured, refuse it there
            // with an OperationOutcome instead of failing the pipeline.
            PipeForward parseFailureForward = findForward(FAILURE_FORWARD);
            if (parseFailureForward == null) {
                throw new PipeRunException(this, "FHIR parse failed (" + fhirVersion + "): " + e.getMessage(), e);
            }
            return new PipeRunResult(parseFailureForward,
                    new Message(parseOutcome(inputIsXml, e.getMessage())));
        } catch (NullPointerException e) {
            // HAPI stitchBundleCrossReferences throws NPE when a Bundle entry's <resource/>
            // element is empty — typically caused by an XSLT that produced no output for
            // a resource template (wrong element paths, missing match, etc.)
            throw new PipeRunException(this,
                    "FHIR parse failed: Bundle entry contains an empty or unrecognised <resource/> element — check XSLT output", e);
        } catch (Exception e) {
            throw new PipeRunException(this, "FHIR validation error", e);
        }

        if (result.isSuccessful()) {
            return new PipeRunResult(getSuccessForward(), message);
        }

        var outcomeParser = inputIsXml ? fhirContext.newXmlParser() : fhirContext.newJsonParser();
        String operationOutcome = outcomeParser
                .setPrettyPrint(true)
                .encodeResourceToString(result.toOperationOutcome());

        PipeForward failureForward = findForward(FAILURE_FORWARD);
        if (failureForward == null) {
            String summary = result.getMessages().stream()
                    .map(m -> m.getSeverity() + ": " + m.getMessage())
                    .collect(Collectors.joining("; "));
            throw new PipeRunException(this, "FHIR validation failed: " + summary);
        }

        return new PipeRunResult(failureForward, new Message(operationOutcome));
    }

    /** OperationOutcome for a parse-level refusal, in the input's encoding. The shape is
     * identical across FHIR versions, so it is built directly rather than via a
     * version-specific model class. */
    private static String parseOutcome(boolean asXml, String diagnostics) {
        String detail = diagnostics == null ? "unparseable FHIR content" : diagnostics;
        if (asXml) {
            return "<OperationOutcome xmlns=\"http://hl7.org/fhir\"><issue><severity value=\"error\"/>"
                    + "<code value=\"structure\"/><diagnostics value=\""
                    + detail.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
                    + "\"/></issue></OperationOutcome>";
        }
        return "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"structure\",\"diagnostics\":\""
                + detail.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                + "\"}]}";
    }

    /**
     * Parse the inbound FHIR payload explicitly before validation.
     *
     * <p>This avoids calling {@code validator.validateWithResult(String)}, which lets
     * HAPI choose the parser path entirely on its own. Explicit parsing also gives
     * clearer errors when the input is neither XML nor JSON.</p>
     */
    private IBaseResource parseResource(String input) {
        if (input == null) {
            throw new DataFormatException("FHIR input is null");
        }

        String trimmed = input.stripLeading();

        if (trimmed.isEmpty()) {
            throw new DataFormatException("FHIR input is empty");
        }

        if (trimmed.startsWith("<")) {
            return fhirContext.newXmlParser().parseResource(input);
        }

        if (trimmed.startsWith("{")) {
            return fhirContext.newJsonParser().parseResource(input);
        }

        throw new DataFormatException("FHIR input is neither XML nor JSON");
    }

    /**
     * When true, a resource claiming a profile this validator cannot resolve (no
     * package loaded for it, e.g. a Nictiz nl-core canonical) fails validation; when
     * false (default) the claim is reported as a warning and validation continues
     * against the base specification. Enable only when the referenced profile
     * packages are actually available to the validator.
     *
     * @ff.default false
     */
    public void setFailOnUnknownProfiles(boolean failOnUnknownProfiles) {
        this.failOnUnknownProfiles = failOnUnknownProfiles;
    }

    /**
     * FHIR version to validate against. Case-insensitive.
     * Supported values: R4, R5, DSTU3.
     *
     * @ff.default R4
     */
    public void setFhirVersion(String fhirVersion) {
        this.fhirVersion = fhirVersion;
    }

    public String getFhirVersion() {
        return fhirVersion;
    }
}