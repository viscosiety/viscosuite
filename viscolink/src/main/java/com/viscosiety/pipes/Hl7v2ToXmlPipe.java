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
 */
public class Hl7v2ToXmlPipe extends FixedForwardPipe {

    private String hl7Version;
    private boolean normalizeLineEndings = true;

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
            String raw = message.asString();
            // HL7v2 segment terminator is CR (\r); browser editors produce LF or CRLF — normalise.
            String hl7 = normalizeLineEndings ? raw.replace("\r\n", "\r").replace("\n", "\r") : raw;
            ca.uhn.hl7v2.model.Message parsed = pipeParser.parse(hl7);
            String xml = xmlParser.encode(parsed);
            return new PipeRunResult(getSuccessForward(), new Message(xml));
        } catch (Exception e) {
            throw new PipeRunException(this, "Failed to convert HL7v2 to XML: " + e.getMessage(), e);
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
     * When omitted the version is read from MSH-12 at parse time.
     * @ff.default (derived from MSH-12)
     */
    public void setHl7Version(String hl7Version) {
        this.hl7Version = hl7Version;
    }

    public String getHl7Version() {
        return hl7Version;
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
}
