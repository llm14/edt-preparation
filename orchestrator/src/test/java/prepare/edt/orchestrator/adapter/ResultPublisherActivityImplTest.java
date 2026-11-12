package prepare.edt.orchestrator.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

/** What lands on Kafka: topic, key, JSON value, headers. MockProducer is Kafka's own test double. */
class ResultPublisherActivityImplTest {

    final MockProducer<String, String> producer =
            new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
    final ResultPublisherActivityImpl publisher = new ResultPublisherActivityImpl(new KafkaEventPublisher(producer),
            new ResultPublisherActivityImpl.Topics("edt.flow-steps", "edt.device-results", "edt.batch-results"));

    @Test
    void a_device_result_is_keyed_by_device_with_its_event_id_in_a_header() {
        publisher.publishDeviceResult(new ResultPublisherActivity.DeviceResult("batch-1/row-3", "2026-11-12T10:00:00Z",
                "batch-1", "00-DB-12-34-56-78-90-A3", 3, "flow-3", "NO_RESPONSE"));

        ProducerRecord<String, String> record = producer.history().get(0);
        assertEquals("edt.device-results", record.topic());
        assertEquals("00-DB-12-34-56-78-90-A3", record.key());
        assertEquals("batch-1/row-3", header(record, "event-id"));
        assertEquals("DeviceResult", header(record, "event-type"));
        assertEquals("{\"eventId\":\"batch-1/row-3\",\"time\":\"2026-11-12T10:00:00Z\",\"batchId\":\"batch-1\","
                + "\"deviceId\":\"00-DB-12-34-56-78-90-A3\",\"rowNumber\":3,\"flow\":\"flow-3\","
                + "\"verdict\":\"NO_RESPONSE\"}", record.value());
    }

    @Test
    void a_step_carries_the_facts_and_a_batch_result_is_keyed_by_batch() {
        publisher.publishFlowStep(new ResultPublisherActivity.FlowStep("batch-1/row-1/step-1", "t", "batch-1",
                "00-DB-12-34-56-78-90-A0", 1, 1, "4.1.1", "R", "RESPONDED", null,
                Map.of("InstantaneousImportRegister", "12345.6")));
        publisher.publishBatchResult(new ResultPublisherActivity.BatchResult("batch-1", "t", "batch-1", 3, 5, 0,
                Map.of("RESPONDS", 2)));

        assertEquals("edt.flow-steps", producer.history().get(0).topic());
        assertEquals("00-DB-12-34-56-78-90-A0", producer.history().get(0).key());
        assertEquals(true, producer.history().get(0).value().contains("\"facts\":{\"InstantaneousImportRegister\":\"12345.6\"}"));
        assertEquals("edt.batch-results", producer.history().get(1).topic());
        assertEquals("batch-1", producer.history().get(1).key());
    }

    @Test
    void when_kafka_refuses_the_activity_fails_so_temporal_retries_it() {
        var failing = new MockProducer<>(false, null, new StringSerializer(), new StringSerializer());
        var publisher = new KafkaEventPublisher(failing);
        Thread.ofVirtual().start(() -> {
            while (failing.history().isEmpty()) {
                Thread.onSpinWait();
            }
            failing.errorNext(new RuntimeException("broker not available"));
        });

        assertThrows(IllegalStateException.class, () -> publisher.publish("t", "k", "e", "x", "{}"));
    }

    static String header(ProducerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
