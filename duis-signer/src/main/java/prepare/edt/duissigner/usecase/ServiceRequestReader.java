package prepare.edt.duissigner.usecase;

import prepare.edt.duissigner.entity.ServiceRequest;

/**
 * Port: reads an unsigned Service Request XML and checks it against the DUIS schema.
 * Implemented by an adapter (XML parsing is technical, so it stays out of the use case).
 */
public interface ServiceRequestReader {

    /** @throws InvalidServiceRequestException if the XML is not valid against the schema */
    ServiceRequest read(String xml);

    /** The XML could not be read or is not valid. The message says why. */
    final class InvalidServiceRequestException extends RuntimeException {

        public InvalidServiceRequestException(String message) {
            super(message);
        }
    }
}
