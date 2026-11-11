package prepare.edt.orchestrator.infrastructure;

import io.smallrye.config.ConfigMapping;

/**
 * The Temporal settings from application.properties (prefix "edt.temporal").
 * Quarkus fills this interface at startup and fails fast if a value is missing.
 */
@ConfigMapping(prefix = "edt.temporal")
public interface TemporalConfig {

    /** Address of the Temporal Server frontend, host:port. */
    String target();

    /** Temporal namespace the EDT workflows live in. */
    String namespace();

    /** Task queue this worker polls for the Orchestrator's workflows (property "task-queue"). */
    String taskQueue();

    /** Task queue of the DUIS Signer, where Sign activities are scheduled (property "signing-task-queue"). */
    String signingTaskQueue();
}
