package com.viscosiety.fhir;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.frankframework.core.ListenerException;
import org.frankframework.core.PipeLineSession;
import org.frankframework.receivers.JavaListener;
import org.frankframework.stream.Message;

/**
 * Spring singleton that bridges HAPI FHIR resource providers to Frank!Framework pipelines.
 *
 * <p>HAPI resource providers call {@link #processRequest(FhirOperation, String)} with the
 * {@link FhirOperation} key identifying the target pipeline and the serialised FHIR XML.
 * The bridge resolves the corresponding {@link FhirListener} from {@link FhirOperationRegistry},
 * creates a fresh {@link PipeLineSession}, and invokes the pipeline synchronously.</p>
 */
public class FhirFfBridge {

    private static final Logger log = LogManager.getLogger(FhirFfBridge.class);

    /**
     * Forward serialised FHIR XML to the F!F pipeline registered for {@code operation} and
     * return the XML response.
     *
     * @param operation identifies the target {@link FhirListener} in the F!F configuration
     * @param fhirXml   the incoming FHIR payload serialised as XML
     * @return the pipeline response serialised as XML
     * @throws ListenerException if no listener is registered for {@code operation}, or if
     *                           the pipeline raises an error
     */
    @SuppressWarnings("unchecked")
    public String processRequest(FhirOperation operation, String fhirXml) throws ListenerException {
        JavaListener<String> listener =
                (JavaListener<String>) FhirOperationRegistry.getListener(operation);
        if (listener == null) {
            throw new ListenerException(
                    "No FhirListener registered for operation [" + operation + "]. " +
                    "Ensure the fhir-to-fhir F!F configuration is loaded and the adapter is running.");
        }
        log.debug("Forwarding {} chars for operation [{}]", fhirXml.length(), operation);
        try (PipeLineSession session = new PipeLineSession()) {
            Message response = listener.processRequest(new Message(fhirXml), session);
            return response.asString();
        } catch (ListenerException e) {
            throw e;
        } catch (Exception e) {
            throw new ListenerException(
                    "Unexpected error processing FHIR request for operation [" + operation + "]", e);
        }
    }
}
