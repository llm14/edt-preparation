package prepare.edt.duissigner.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the one line for a boundary crossing (a call to or from another system) on the logger {@code edt.interaction}.
 * It sets the keys every such line has (event.action, edt.interface, network.direction, event.outcome, event.duration)
 * and takes them out of the MDC when the block closes. Use it beside {@link LogFields} for the identifiers.
 *
 * <pre>
 * try (var call = Interaction.outbound("edt.orchestratorReqHttp", "service-request-sent")) {
 *     // ... make the call ...
 *     call.log("Service Request posted");
 * }
 * </pre>
 *
 * The duration is measured from the moment the helper is created to the moment {@link #log} is called. The outcome is
 * success unless {@link #failure()} was called first.
 */
public final class Interaction implements AutoCloseable {

    /** The logger name the line is written on; its level is set in application.properties. */
    public static final String LOGGER = "edt.interaction";

    private static final Logger LOG = LoggerFactory.getLogger(LOGGER);

    private final LogFields fields;
    private final long startedAt = System.nanoTime();
    private boolean failed;

    private Interaction(String edtInterface, String action, String direction) {
        this.fields = LogFields.of("event.action", action, "edt.interface", edtInterface, "network.direction", direction);
    }

    /** A call this container makes. {@code edtInterface} is the interface ID of the architecture model. */
    public static Interaction outbound(String edtInterface, String action) {
        return new Interaction(edtInterface, action, "outbound");
    }

    /** A call this container receives. */
    public static Interaction inbound(String edtInterface, String action) {
        return new Interaction(edtInterface, action, "inbound");
    }

    /** Adds an identifier or result to the line. */
    public Interaction with(String key, Object value) {
        fields.add(key, value);
        return this;
    }

    /** Marks the call as failed: the line gets event.outcome failure. */
    public Interaction failure() {
        failed = true;
        return this;
    }

    /** Writes the INFO line with the duration so far and the outcome. */
    public void log(String message) {
        fields.add("event.outcome", failed ? "failure" : "success");
        fields.add("event.duration", System.nanoTime() - startedAt);
        LOG.info(message);
    }

    @Override
    public void close() {
        fields.close();
    }
}
