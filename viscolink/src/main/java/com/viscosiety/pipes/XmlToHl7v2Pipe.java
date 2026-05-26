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

import org.jspecify.annotations.NonNull;

import org.frankframework.configuration.ConfigurationException;
import org.frankframework.core.PipeLineSession;
import org.frankframework.core.PipeRunException;
import org.frankframework.core.PipeRunResult;
import org.frankframework.pipes.FixedForwardPipe;
import org.frankframework.stream.Message;

/**
 * Converts an HL7v2 XML Encoding Syntax message ({@code urn:hl7-org:v2xml}) back to
 * pipe-delimited HL7v2 format using the HAPI HL7v2 library.
 *
 * <p>Typically placed before {@link com.viscosiety.mllp.MllpSender} to produce a wire-ready
 * payload, or to build a pipe-delimited ACK response that the
 * {@link com.viscosiety.mllp.MllpListener} will frame and send back to the originating system.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * <!-- Build an ACK in XML (e.g. via XSLT), then convert to pipe-delimited for wire -->
 * <XmlToHl7v2Pipe name="buildAck" hl7Version="2.5" />
 * }</pre>
 */
public class XmlToHl7v2Pipe extends FixedForwardPipe {

    private String hl7Version;

    private PipeParser pipeParser;
    private XMLParser  xmlParser;

    @Override
    public void configure() throws ConfigurationException {
        super.configure();
        try {
            HapiContext ctx = buildContext();
            pipeParser = ctx.getPipeParser();
            xmlParser  = ctx.getXMLParser();
        } catch (Exception e) {
            throw new ConfigurationException("Failed to initialise HAPI HL7v2 parsers: " + e.getMessage(), e);
        }
    }

    @Override
    public @NonNull PipeRunResult doPipe(@NonNull Message message, @NonNull PipeLineSession session)
            throws PipeRunException {
        try {
            String xml = message.asString();
            ca.uhn.hl7v2.model.Message parsed = xmlParser.parse(xml);
            String hl7 = pipeParser.encode(parsed);
            return new PipeRunResult(getSuccessForward(), new Message(hl7));
        } catch (Exception e) {
            throw new PipeRunException(this, "Failed to convert HL7v2 XML to pipe-delimited: " + e.getMessage(), e);
        }
    }

    private HapiContext buildContext() {
        if (hl7Version == null || hl7Version.isBlank()) {
            return new DefaultHapiContext();
        }
        return new DefaultHapiContext(new CanonicalModelClassFactory(hl7Version));
    }

    /**
     * HL7v2 version to use when resolving message structures (e.g. {@code 2.5} or {@code 2.5.1}).
     * When omitted the version is read from the XML message itself.
     * @ff.default (derived from message)
     */
    public void setHl7Version(String hl7Version) {
        this.hl7Version = hl7Version;
    }

    public String getHl7Version() {
        return hl7Version;
    }
}
