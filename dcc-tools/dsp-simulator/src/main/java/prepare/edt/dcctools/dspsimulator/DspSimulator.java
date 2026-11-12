package prepare.edt.dcctools.dspsimulator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Plays the DSP HTTPS Gateway, the DSP and the devices for local development (architecture model, 010.030).
 *
 * Outbound (EDT -> DSP):  POST /duis/service-requests with a signed Service Request.
 *                         Returns the synchronous DUIS response at once (accepted or rejected).
 * Inbound (DSP -> EDT):   later, POSTs the Response or DCC Alert to the Response Listener
 *                         (EDT_RESPONSE_LISTENER_URL), as the gateway does. Retries a few times on a transient
 *                         error (no connection, or HTTP 5xx: the Response Listener answers 500 when Temporal is
 *                         unavailable); an HTTP 4xx means the message was refused, so it is not sent again.
 * Every message the DSP sends is signed with the DSP's key (DspSigner).
 * What happens depends on the target device ID: see DeviceBehaviour.
 *
 * Configuration (environment variables):
 *   EDT_PORT                   HTTP port (default 8090)
 *   EDT_RESPONSE_LISTENER_URL  where to post Responses and DCC Alerts
 *   EDT_RESPONSE_DELAY_SECONDS delay before each asynchronous message (default 5): the device is not always listening
 *   EDT_DSP_KEYSTORE           PKCS#12 file with the DSP's signing key (written by kms-setup)
 *   EDT_DSP_KEYSTORE_PASSWORD  its password
 */
public final class DspSimulator {

    private static final int DELIVERY_ATTEMPTS = 5;
    private static final Duration DELIVERY_RETRY_DELAY = Duration.ofSeconds(3);

    private final URI responseListener;
    private final Duration responseDelay;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final DspSigner signer;

    DspSimulator(URI responseListener, Duration responseDelay, DspSigner signer) {
        this.responseListener = responseListener;
        this.responseDelay = responseDelay;
        this.signer = signer;
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(env("EDT_PORT", "8090"));
        var signer = DspSigner.load(
                Path.of(env("EDT_DSP_KEYSTORE", "/dsp-keys/dsp-signing.p12")),
                env("EDT_DSP_KEYSTORE_PASSWORD", "edt-test-only").toCharArray(),
                "dsp");
        var simulator = new DspSimulator(
                URI.create(env("EDT_RESPONSE_LISTENER_URL", "http://response-listener:8084/duis/responses")),
                Duration.ofSeconds(Long.parseLong(env("EDT_RESPONSE_DELAY_SECONDS", "5"))),
                signer);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/duis/service-requests", simulator::handleServiceRequest);
        server.createContext("/health", exchange -> reply(exchange, 200, "text/plain", "UP"));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        log("event.action=started port=%d response_listener=%s response_delay=%s",
                port, simulator.responseListener, simulator.responseDelay);
    }

    /** The synchronous part: read the request, decide, answer at once, and schedule the asynchronous messages. */
    void handleServiceRequest(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            reply(exchange, 405, "text/plain", "POST only");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        DspMessages.ServiceRequest request;
        try {
            request = DspMessages.read(body);
        } catch (Exception e) {
            reply(exchange, 400, "text/plain", "cannot read Service Request: " + e.getMessage());
            return;
        }

        DeviceBehaviour behaviour = DeviceBehaviour.forTarget(request.targetDevice());
        boolean accepted = request.signed() && behaviour.accepted;
        String detail = !request.signed() ? "not signed" : accepted ? "accepted for delivery" : "rejected (simulated)";
        log("event.action=service-request-received request_id=%s target=%s behaviour=%s accepted=%s",
                request.requestId(), request.targetDevice(), behaviour, accepted);

        String syncResponse = signer.sign(DspMessages.syncResponse(request.requestId(), accepted, detail));
        if (behaviour.tamperSync) {
            syncResponse = DspMessages.tamper(syncResponse);
        }
        reply(exchange, 200, "application/xml", syncResponse);

        if (accepted && !behaviour.tamperSync) {
            scheduleAsyncMessages(request.requestId(), behaviour);
        }
    }

    /** The asynchronous part: each message after responseDelay, one after the other. */
    private void scheduleAsyncMessages(String requestId, DeviceBehaviour behaviour) {
        long delay = 0;
        for (String message : behaviour.asyncMessages) {
            delay += responseDelay.toMillis();
            String xml = signer.sign("RESPONSE".equals(message)
                    ? DspMessages.response(requestId)
                    : DspMessages.dccAlert(requestId, message));
            if (behaviour.tamperAsync) {
                xml = DspMessages.tamper(xml);
            }
            String toSend = xml;
            scheduler.schedule(() -> deliver(requestId, message, toSend, 1), delay, TimeUnit.MILLISECONDS);
        }
    }

    /** Posts one message to the Response Listener; on failure, tries again later (like the gateway). */
    private void deliver(String requestId, String kind, String xml, int attempt) {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(responseListener)
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/xml")
                            .POST(HttpRequest.BodyPublishers.ofString(xml))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                log("event.action=delivered request_id=%s message=%s attempt=%d", requestId, kind, attempt);
                return;
            }
            if (response.statusCode() / 100 == 4) {
                // Refused (for example a bad signature): sending it again would not help.
                log("event.action=delivery-refused request_id=%s message=%s status=%d", requestId, kind,
                        response.statusCode());
                return;
            }
            retryOrDrop(requestId, kind, xml, attempt, "HTTP " + response.statusCode());
        } catch (Exception e) {
            retryOrDrop(requestId, kind, xml, attempt, e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private void retryOrDrop(String requestId, String kind, String xml, int attempt, String reason) {
        if (attempt < DELIVERY_ATTEMPTS) {
            log("event.action=delivery-retry request_id=%s message=%s attempt=%d reason=\"%s\"",
                    requestId, kind, attempt, reason);
            scheduler.schedule(() -> deliver(requestId, kind, xml, attempt + 1),
                    DELIVERY_RETRY_DELAY.toMillis(), TimeUnit.MILLISECONDS);
        } else {
            log("event.action=delivery-dropped request_id=%s message=%s attempts=%d reason=\"%s\"",
                    requestId, kind, attempt, reason);
        }
    }

    private static void reply(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void log(String format, Object... args) {
        System.out.println(String.format(format, args));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
