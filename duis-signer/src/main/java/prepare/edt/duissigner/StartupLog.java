package prepare.edt.duissigner;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

/**
 * Placeholder: logs one line when the application starts.
 * Replaced later by the Temporal worker that runs the Sign activity.
 */
@ApplicationScoped
public class StartupLog {

    private static final Logger LOG = Logger.getLogger(StartupLog.class);

    void onStart(@Observes StartupEvent event) {
        LOG.info("DUIS Signer started");
    }
}
