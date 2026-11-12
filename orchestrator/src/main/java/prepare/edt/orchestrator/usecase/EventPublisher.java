package prepare.edt.orchestrator.usecase;

/** Port: publishes one event to a topic (Kafka). Returns once the broker has stored it; throws otherwise. */
public interface EventPublisher {

    /**
     * @param topic   where
     * @param key     decides the partition: events with the same key stay in order (a device)
     * @param eventId lets a consumer drop a duplicate (a retried publish can send the same event twice)
     * @param type    what kind of event (also clear from the topic)
     * @param json    the event
     */
    void publish(String topic, String key, String eventId, String type, String json);
}
