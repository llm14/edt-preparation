package prepare.edt.batchingest.infrastructure;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

/**
 * Placeholder: logs one structured line when the application starts.
 * Replaced later by the consumer of the Manifest events.
 */
@ApplicationScoped
public class StartupLog {

    private static final Logger LOG = Logger.getLogger(StartupLog.class);

    void onStart(@Observes StartupEvent event) {
        try (var fields = LogFields.of("event.action", "startup")) {
            LOG.info("Batch Ingest started");
        }
    }
}
