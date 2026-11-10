package prepare.edt.responselistener.infrastructure;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

/**
 * Placeholder: logs one structured line when the application starts.
 * Replaced later by the HTTP endpoint that receives Responses and DCC Alerts.
 */
@ApplicationScoped
public class StartupLog {

    private static final Logger LOG = Logger.getLogger(StartupLog.class);

    void onStart(@Observes StartupEvent event) {
        try (var fields = LogFields.of("event.action", "startup")) {
            LOG.info("Response Listener started");
        }
    }
}
