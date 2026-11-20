package prepare.edt.orchestrator.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import prepare.edt.orchestrator.infrastructure.Interaction;
import prepare.edt.orchestrator.usecase.EventPublisher;

/** Turns each event into JSON and publishes it to its topic, keyed by device (a Batch result: by Batch). */
public class ResultPublisherActivityImpl implements ResultPublisherActivity {

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
        try (var call = Interaction.outbound("edt.orchestratorReqKafka", "result-published")
                .with("edt.kafka.topic", topic).with("edt.event_id", eventId).with("edt.event_type", type)) {
            publisher.publish(topic, key, eventId, type, json);
            call.log("Published to Kafka");
        }
    }

    public record Topics(String flowSteps, String deviceResults, String batchResults) {
    }
}
