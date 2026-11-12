package prepare.edt.orchestrator.infrastructure;

import io.smallrye.config.ConfigMapping;

/** Kafka settings (prefix "edt.kafka"). [CONFIRM] topic names, and mutual TLS, with DCC. */
@ConfigMapping(prefix = "edt.kafka")
public interface KafkaConfig {

    /** Brokers to start from, host:port (the client then learns the others from them). */
    String bootstrapServers();

    String flowStepsTopic();

    String deviceResultsTopic();

    String batchResultsTopic();
}
