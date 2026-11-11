package prepare.edt.orchestrator.infrastructure;

import io.smallrye.config.ConfigMapping;
import java.net.URI;
import java.time.Duration;

/** Settings of the Service Request workflow and of the DUIS Sender (prefix "edt.service-request"). */
@ConfigMapping(prefix = "edt.service-request")
public interface ServiceRequestConfig {

    /** How long to wait for the asynchronous Response. Must be longer than the DSP's retry period (model). */
    Duration responseTimeout();

    /** The DSP HTTPS Gateway's endpoint for Service Requests (plain HTTP; the gateway does the TLS to the DSP). */
    URI gatewayUrl();

    /**
     * Maximum Service Requests per second sent to the DSP, across all Orchestrator replicas (property
     * "send-rate-per-second"). The Temporal Server enforces it on the send task queue. [CONFIRM] the value with DCC.
     */
    double sendRatePerSecond();
}
