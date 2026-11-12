package prepare.edt.batchingest.infrastructure;

import io.smallrye.config.ConfigMapping;

/** The Temporal settings (prefix "edt.temporal"). Batch Ingest is a client only: it runs no worker. */
@ConfigMapping(prefix = "edt.temporal")
public interface TemporalConfig {

    /** Address of the Temporal Server frontend, host:port. */
    String target();

    /** Temporal namespace the EDT workflows live in. */
    String namespace();

    /** The Orchestrator's task queue, where the Batch workflow runs (property "batch-task-queue"). */
    String batchTaskQueue();
}
