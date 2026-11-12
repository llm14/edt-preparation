package prepare.edt.responselistener.infrastructure;

import io.smallrye.config.ConfigMapping;

/**
 * The Temporal settings from application.properties (prefix "edt.temporal").
 * No task queue: the Response Listener only signals workflows, it is not a worker.
 */
@ConfigMapping(prefix = "edt.temporal")
public interface TemporalConfig {

    /** Address of the Temporal Server frontend, host:port. */
    String target();

    /** Temporal namespace the EDT workflows live in. */
    String namespace();
}
