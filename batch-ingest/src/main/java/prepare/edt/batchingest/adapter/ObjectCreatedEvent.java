package prepare.edt.batchingest.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import prepare.edt.batchingest.entity.ManifestRef;

/**
 * Reads the "Object Created" message EventBridge puts on the queue for each upload to the Manifest bucket.
 * Only three fields matter: detail.bucket.name, detail.object.key and detail.object.version-id.
 */
final class ObjectCreatedEvent {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ObjectCreatedEvent() {
    }

    static ManifestRef parse(String body) throws UnreadableEventException {
        JsonNode event;
        try {
            event = JSON.readTree(body);
        } catch (Exception e) {
            throw new UnreadableEventException("not JSON");
        }
        if (event == null || !"Object Created".equals(event.path("detail-type").asText())) {
            throw new UnreadableEventException("not an Object Created event");
        }
        JsonNode detail = event.path("detail");
        try {
            return new ManifestRef(
                    detail.path("bucket").path("name").asText(null),
                    detail.path("object").path("key").asText(null),
                    detail.path("object").path("version-id").asText(null));
        } catch (IllegalArgumentException e) {   // a field is missing: ManifestRef refuses it
            throw new UnreadableEventException(e.getMessage());
        }
    }

    static final class UnreadableEventException extends Exception {
        UnreadableEventException(String message) {
            super(message);
        }
    }
}
