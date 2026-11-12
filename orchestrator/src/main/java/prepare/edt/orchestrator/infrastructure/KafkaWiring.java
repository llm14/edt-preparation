package prepare.edt.orchestrator.infrastructure;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import prepare.edt.orchestrator.adapter.KafkaEventPublisher;
import prepare.edt.orchestrator.adapter.ResultPublisherActivityImpl;

/**
 * The Kafka producer and the Result Publisher activity. One producer for the whole application: it is thread-safe
 * and batches messages from all activities.
 */
@ApplicationScoped
public class KafkaWiring {

    @Produces
    @Singleton
    Producer<String, String> producer(KafkaConfig kafka) {
        Map<String, Object> settings = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers(),
                ProducerConfig.CLIENT_ID_CONFIG, "orchestrator",
                // Key and value are text: the key a device ID, the value JSON.
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                // acks=all: the broker answers once every in-sync copy has the message (min.insync.replicas).
                ProducerConfig.ACKS_CONFIG, "all",
                // Idempotence: the client's own retries never write a message twice. (A retried ACTIVITY can still
                // send it again: that is what the event-id header is for.)
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                // Give up within the activity's 30 s: then Temporal retries the activity.
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) Duration.ofSeconds(20).toMillis(),
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) Duration.ofSeconds(10).toMillis(),
                ProducerConfig.MAX_BLOCK_MS_CONFIG, (int) Duration.ofSeconds(10).toMillis());
        return new KafkaProducer<>(settings);
    }

    @Produces
    @Singleton
    ResultPublisherActivityImpl resultPublisher(Producer<String, String> producer, KafkaConfig kafka) {
        return new ResultPublisherActivityImpl(new KafkaEventPublisher(producer),
                new ResultPublisherActivityImpl.Topics(kafka.flowStepsTopic(), kafka.deviceResultsTopic(),
                        kafka.batchResultsTopic()));
    }

    /** Sends what is still buffered, then closes. */
    void close(@Disposes Producer<String, String> producer) {
        producer.close(Duration.ofSeconds(5));
    }
}
