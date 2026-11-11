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
}
