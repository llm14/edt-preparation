package prepare.edt.orchestrator.entity;

import java.util.Set;

/**
 * Which DCC Alerts end a Service Request as failed.
 *
 * The architecture model: "A DCC Alert N12 or N13, or the timeout, marks it failed."
 * Other alerts (for example N53, which the Response Listener also routes) do not end it:
 * the workflow keeps waiting for the Response. TO CONFIRM: what each other alert should do.
 */
public final class DccAlerts {

    private static final Set<String> FAILING = Set.of("N12", "N13");

    private DccAlerts() {
    }

    public static boolean failsServiceRequest(String alertCode) {
        return FAILING.contains(alertCode);
    }
}
