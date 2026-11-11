package prepare.edt.responselistener.infrastructure;

import io.temporal.client.WorkflowClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import prepare.edt.responselistener.adapter.DspSignatureVerifier;
import prepare.edt.responselistener.adapter.TemporalWorkflowSignaller;
import prepare.edt.responselistener.adapter.XmlDspSignatureCheck;
import prepare.edt.responselistener.adapter.XmlDspMessageReader;
import prepare.edt.responselistener.usecase.RouteDspMessage;

/**
 * Builds the use case with its adapters. The use case and adapters have no CDI annotations:
 * this is the one place that decides which implementation fills each port.
 */
@ApplicationScoped
public class RoutingWiring {

    @Produces
    @Singleton
    RouteDspMessage routeDspMessage(WorkflowClient client, DspSignatureVerifier verifier) {
        return new RouteDspMessage(new XmlDspSignatureCheck(verifier), new XmlDspMessageReader(),
                new TemporalWorkflowSignaller(client));
    }
}
