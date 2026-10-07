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

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.parser.CanonicalModelClassFactory;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.parser.XMLParser;
import ca.uhn.hl7v2.validation.impl.NoValidation;

import org.jspecify.annotations.NonNull;

import org.frankframework.configuration.ConfigurationException;
import org.frankframework.core.PipeLineSession;
import org.frankframework.core.PipeRunException;
import org.frankframework.core.PipeRunResult;
import org.frankframework.parameters.ParameterValueList;
import org.frankframework.pipes.FixedForwardPipe;
import org.frankframework.stream.Message;

/**
 * Converts a pipe-delimited HL7v2 message to HL7v2 XML Encoding Syntax
 * ({@code urn:hl7-org:v2xml}) using the HAPI HL7v2 library.
 *
 * <p>Configure {@code hl7Version} to enforce message structure resolution against a
 * specific HL7v2 version. If omitted, the version is derived from MSH-12.</p>
 *
 * <p>Usage in a pipeline that receives raw HL7v2 from {@link com.viscosiety.mllp.MllpListener}:</p>
 * <pre>{@code
 * <Hl7v2ToXmlPipe name="parseHl7" hl7Version="2.5" />
 * }</pre>
 *
 * <p>The output XML is consumed by XSLTs in the {@code urn:hl7-org:v2xml} namespace.</p>
 *
 * @ff.parameter validateMessage overrides attribute <code>validateMessage</code> for the
 *               current message (e.g. from a session key or an XPath over the message).
 */
public class Hl7v2ToXmlPipe extends FixedForwardPipe {

    private String hl7Version;
    private boolean enforceHl7Version = true;
    private boolean normalizeLineEndings = true;
    private boolean validateMessage = true;

    private PipeParser pipeParserValidating;
    private XMLParser  xmlParserValidating;
    private PipeParser pipeParserNoValidation;
    private XMLParser  xmlParserNoValidation;

    @Override
    public void configure() throws ConfigurationException {
        super.configure();
        try {
            HapiContext ctxValidating = buildContext(true);
            pipeParserValidating = ctxValidating.getPipeParser();
            xmlParserValidating  = ctxValidating.getXMLParser();

            HapiContext ctxNoValidation = buildContext(false);
            pipeParserNoValidation = ctxNoValidation.getPipeParser();
            xmlParserNoValidation  = ctxNoValidation.getXMLParser();
        } catch (Exception e) {
            throw new ConfigurationException("Failed to initialise HAPI HL7v2 parsers: " + e.getMessage(), e);
        }
    }

    @Override
    public @NonNull PipeRunResult doPipe(@NonNull Message message, @NonNull PipeLineSession session)
            throws PipeRunException {
        if (message == null) {
            throw new PipeRunException(this, "Failed to convert HL7v2 to XML: Message can not be null!");
        }
        try {
            boolean validate = validateMessage;
            ParameterValueList pvl = getParameterList().getValues(message, session);
            if (pvl != null && pvl.contains("validateMessage")) {
                String raw = pvl.get("validateMessage").asStringValue();
                if (raw != null && !raw.isBlank()) {
                    validate = Boolean.parseBoolean(raw);
                }
            }

            String raw = message.asString();
            if (raw == null || raw.isBlank()) {
                throw new PipeRunException(this, "Failed to convert HL7v2 to XML: HL7v2 message is empty");
            }
            // HL7v2 segment terminator is CR (\r); browser editors produce LF or CRLF — normalise.
            String hl7 = normalizeLineEndings ? raw.replace("\r\n", "\r").replace("\n", "\r") : raw;
            PipeParser pipeParser = validate ? pipeParserValidating : pipeParserNoValidation;
            XMLParser  xmlParser  = validate ? xmlParserValidating  : xmlParserNoValidation;
            ca.uhn.hl7v2.model.Message parsed = pipeParser.parse(hl7);
            if (enforceHl7Version && hl7Version != null && !hl7Version.isBlank()) {
                String msgVersion = parsed.getVersion();
                if (!hl7Version.equals(msgVersion)) {
                    throw new PipeRunException(this,
                        "Failed to convert HL7v2 to XML: message version " + msgVersion +
                        " does not match configured hl7Version " + hl7Version);
                }
            }
            String xml = xmlParser.encode(parsed);
            return new PipeRunResult(getSuccessForward(), new Message(xml));
        } catch (PipeRunException e) {
            throw e;
        } catch (Exception e) {
            throw new PipeRunException(this, "Failed to convert HL7v2 to XML: " + e.getMessage(), e);
        }
    }

    private HapiContext buildContext(boolean validate) {
        // When enforceHl7Version is false, don't pin the model factory to hl7Version —
        // let HAPI resolve the message structure from MSH-12 so the correct version's
        // validation rules and field definitions are applied.
        boolean pinVersion = enforceHl7Version && hl7Version != null && !hl7Version.isBlank();
        HapiContext ctx = pinVersion
                ? new DefaultHapiContext(new CanonicalModelClassFactory(hl7Version))
                : new DefaultHapiContext();
        if (!validate) {
            ctx.setValidationContext(new NoValidation());
        }
        return ctx;
    }

    /**
     * HL7v2 version to use when resolving message structures (e.g. {@code 2.5} or {@code 2.5.1}).
     * When set, messages whose MSH-12 version does not match are rejected with a {@link PipeRunException}.
     * When omitted the version is derived from MSH-12 and no version check is performed.
     * @ff.default (derived from MSH-12)
     */
    public void setHl7Version(String hl7Version) {
        this.hl7Version = hl7Version;
    }

    public String getHl7Version() {
        return hl7Version;
    }

    /**
     * When {@code false}, the version check against MSH-12 is skipped and HAPI derives the
     * message structure and validation rules from MSH-12 rather than from {@code hl7Version}.
     * Use this when senders are known to send an older minor version (e.g. 2.5) but the pipeline
     * is configured for a newer one (e.g. 2.6) — the message is parsed against its own version's
     * rules, so no withdrawn-field violations are triggered.
     * @ff.default true
     */
    public void setEnforceHl7Version(boolean enforceHl7Version) {
        this.enforceHl7Version = enforceHl7Version;
    }

    public boolean isEnforceHl7Version() {
        return enforceHl7Version;
    }

    /**
     * When {@code true}, CR+LF and bare LF line endings are normalised to CR before parsing,
     * working around browser-based editors that mangle HL7v2 segment terminators.
     * @ff.default true
     */
    public void setNormalizeLineEndings(boolean normalizeLineEndings) {
        this.normalizeLineEndings = normalizeLineEndings;
    }

    public boolean isNormalizeLineEndings() {
        return normalizeLineEndings;
    }

    /**
     * When {@code false}, HAPI HL7v2 validation is disabled. Useful for processing
     * messages that use withdrawn fields or otherwise fail strict HL7v2 validation.
     * @ff.default true
     */
    public void setValidateMessage(boolean validateMessage) {
        this.validateMessage = validateMessage;
    }

    public boolean isValidateMessage() {
        return validateMessage;
    }
}
