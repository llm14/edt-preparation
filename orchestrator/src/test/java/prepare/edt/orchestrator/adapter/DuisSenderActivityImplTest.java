package prepare.edt.orchestrator.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The sender against a fake DSP HTTPS Gateway (the JDK's own HTTP server, on a free local port). */
class DuisSenderActivityImplTest {

    HttpServer gateway;
    final AtomicReference<String> answer = new AtomicReference<>();
    final AtomicInteger status = new AtomicInteger(200);
    final AtomicInteger posts = new AtomicInteger();
    final AtomicReference<String> received = new AtomicReference<>();
    DuisSenderActivityImpl sender;

    @BeforeEach
    void startGateway() throws Exception {
        gateway = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        gateway.createContext("/duis/service-requests", exchange -> {
            posts.incrementAndGet();
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = answer.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        gateway.start();
        sender = new DuisSenderActivityImpl(
                URI.create("http://localhost:" + gateway.getAddress().getPort() + "/duis/service-requests"));
    }

    @AfterEach
    void stopGateway() {
        gateway.stop(0);
    }

    @Test
    void posts_the_signed_request_and_reads_an_acceptance() {
        answer.set(sync("I0", "accepted for delivery"));

        SyncResponse response = sender.send("<ServiceRequest/>");

        assertTrue(response.accepted());
        assertEquals("I0", response.responseCode());
        assertEquals("<ServiceRequest/>", received.get());
        assertEquals(1, posts.get());
    }

    @Test
    void reads_a_rejection() {
        answer.set(sync("E99", "rejected (simulated)"));

        SyncResponse response = sender.send("<ServiceRequest/>");

        assertFalse(response.accepted());
        assertEquals("rejected (simulated)", response.detail());
    }

    @Test
    void an_http_error_throws_and_is_posted_only_once() {
        answer.set("gateway error");
        status.set(500);

        assertThrows(IllegalStateException.class, () -> sender.send("<ServiceRequest/>"));
        assertEquals(1, posts.get(), "the sender itself never retries");
    }

    static String sync(String code, String detail) {
        return "<SyncResponse xmlns=\"urn:edt:placeholder:duis\"><RequestId>R-1</RequestId>"
                + "<ResponseCode>" + code + "</ResponseCode><Detail>" + detail + "</Detail></SyncResponse>";
    }
}
