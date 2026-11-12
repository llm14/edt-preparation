package prepare.edt.orchestrator.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import prepare.edt.orchestrator.infrastructure.LogFields;
import prepare.edt.orchestrator.usecase.EventPublisher;

/** Turns each event into JSON and publishes it to its topic, keyed by device (a Batch result: by Batch). */
public class ResultPublisherActivityImpl implements ResultPublisherActivity {

    private static final Logger LOG = Logger.getLogger(ResultPublisherActivityImpl.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final EventPublisher publisher;
    private final Topics topics;

    public ResultPublisherActivityImpl(EventPublisher publisher, Topics topics) {
        this.publisher = publisher;
        this.topics = topics;
    }

    @Override
    public void publishFlowStep(FlowStep event) {
        publish(topics.flowSteps(), event.deviceId(), event.eventId(), "FlowStep", event);
    }

    @Override
    public void publishDeviceResult(DeviceResult event) {
        publish(topics.deviceResults(), event.deviceId(), event.eventId(), "DeviceResult", event);
    }

    /** [INFERRED] A Batch result has no device: keyed by Batch ID. */
    @Override
    public void publishBatchResult(BatchResult event) {
        publish(topics.batchResults(), event.batchId(), event.eventId(), "BatchResult", event);
    }

    private void publish(String topic, String key, String eventId, String type, Object event) {
        String json;
        try {
            json = JSON.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write " + type + " as JSON", e);
        }
        publisher.publish(topic, key, eventId, type, json);
        try (var fields = LogFields.of("event.action", "result-published", "edt.kafka.topic", topic,
                "edt.event_id", eventId, "edt.event_type", type)) {
            LOG.info("Published to Kafka");
        }
    }

    public record Topics(String flowSteps, String deviceResults, String batchResults) {
    }
}
