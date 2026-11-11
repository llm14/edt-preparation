package prepare.edt.batchingest.infrastructure;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.Duration;
import org.jboss.logging.Logger;
import prepare.edt.batchingest.adapter.SqsManifestEventConsumer;

/**
 * Runs the Manifest Event Consumer on one thread for the life of the application: long poll, handle, repeat.
 *
 * If the queue cannot be reached (SQS down, queue not created yet), it logs and waits a few seconds before trying
 * again, instead of a tight loop. More replicas mean more pollers: SQS gives each message to one of them at a time.
 */
@ApplicationScoped
public class ManifestEventPolling {

    private static final Logger LOG = Logger.getLogger(ManifestEventPolling.class);
    private static final Duration PAUSE_AFTER_ERROR = Duration.ofSeconds(5);

    private final SqsManifestEventConsumer consumer;
    private volatile boolean running;
    private Thread thread;

    ManifestEventPolling(SqsManifestEventConsumer consumer) {
        this.consumer = consumer;
    }

    void onStart(@Observes StartupEvent event) {
        running = true;
        thread = Thread.ofPlatform().name("manifest-events").start(this::loop);
    }

    void onStop(@Observes ShutdownEvent event) throws InterruptedException {
        running = false;
        thread.interrupt();
        // A long poll can last up to 20 s; a message being handled is finished, or comes back later if not deleted.
        thread.join(Duration.ofSeconds(25));
    }

    private void loop() {
        try (var fields = LogFields.of("event.action", "manifest-polling-started")) {
            LOG.info("Polling the Manifest Events queue");
        }
        while (running) {
            try {
                consumer.pollOnce();
            } catch (RuntimeException e) {
                if (!running) {
                    break;
                }
                try (var fields = LogFields.of("event.action", "manifest-poll-failed", "event.outcome", "failure",
                        "error.message", String.valueOf(e.getMessage()))) {
                    LOG.warn("Cannot poll the Manifest Events queue; trying again shortly");
                }
                try {
                    Thread.sleep(PAUSE_AFTER_ERROR);
                } catch (InterruptedException interrupted) {
                    break;
                }
            }
        }
    }
}
