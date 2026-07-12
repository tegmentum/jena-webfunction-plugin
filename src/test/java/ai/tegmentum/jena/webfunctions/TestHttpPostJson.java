package ai.tegmentum.jena.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;
import ai.tegmentum.webassembly4j.api.WitHostFunction;

import com.sun.net.httpserver.HttpServer;

import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link HostCallbacks#httpPostJson()} callback backing
 * the {@code wf:fulltext/host@0.1.0#http-post-json} import.
 *
 * <p>Spins a tiny {@link HttpServer} bound to {@code 127.0.0.1:0} (kernel-
 * assigned port) and exercises the callback end-to-end so the error contract
 * and the success round-trip are verified against an actual TCP connection.
 * Mirrors the reference test at
 * {@code wf_fulltext/tests/manticore_client.rs::wire_round_trip_via_local_tcp_listener}.
 */
public class TestHttpPostJson {

    @Test
    public void successRoundTripsBodyAndResponse() throws Exception {
        final AtomicReference<String> received = new AtomicReference<>();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            final byte[] body = exchange.getRequestBody().readAllBytes();
            received.set(new String(body, StandardCharsets.UTF_8));
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Content-Type"))
                    .isEqualTo("application/json");
            final String response = "{\"hits\":{\"hits\":[{\"_id\":\"1\",\"_score\":0.9}]}}";
            final byte[] respBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, respBytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(respBytes);
            }
        });
        server.start();
        try {
            final String url = "http://" + server.getAddress().getHostString()
                    + ":" + server.getAddress().getPort() + "/search";
            final String body = "{\"table\":\"docs\",\"query\":{\"match\":{\"*\":\"fox\"}}}";

            final WitHostFunction fn = HostCallbacks.httpPostJson();
            final Object[] out = fn.execute(new Object[] {
                ComponentVal.string(url), ComponentVal.string(body) });
            assertThat(out).hasSize(1);
            final ComponentVal result = (ComponentVal) out[0];

            // Result is `result<string, string>` — ok arm carries the body.
            final ComponentVal ok = result.asResult().getOk()
                    .orElseThrow(() -> new AssertionError(
                        "expected Ok, got err: " + result.asResult().getErr()));
            assertThat(ok.asString()).contains("\"_id\":\"1\"");
            assertThat(received.get()).isEqualTo(body);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void nonTwoXxSurfacesAsHttpCodeError() throws Exception {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            exchange.getRequestBody().readAllBytes();
            final byte[] body = "brew".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(418, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            final String url = "http://" + server.getAddress().getHostString()
                    + ":" + server.getAddress().getPort() + "/search";
            final WitHostFunction fn = HostCallbacks.httpPostJson();
            final Object[] out = fn.execute(new Object[] {
                ComponentVal.string(url), ComponentVal.string("{}") });
            final ComponentVal result = (ComponentVal) out[0];
            final ComponentVal err = result.asResult().getErr()
                    .orElseThrow(() -> new AssertionError(
                        "expected Err, got ok: " + result.asResult().getOk()));
            assertThat(err.asString()).startsWith("HTTP 418");
            assertThat(err.asString()).contains("brew");
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void transportErrorSurfacesAsHttpTransport() throws Exception {
        // Nothing is listening at port 1 — connect fails immediately on
        // every platform the test suite runs on (macOS + Linux).
        final WitHostFunction fn = HostCallbacks.httpPostJson();
        final Object[] out = fn.execute(new Object[] {
            ComponentVal.string("http://127.0.0.1:1/search"),
            ComponentVal.string("{}") });
        final ComponentVal result = (ComponentVal) out[0];
        final ComponentVal err = result.asResult().getErr()
                .orElseThrow(() -> new AssertionError("expected Err"));
        assertThat(err.asString()).startsWith("http transport: ");
    }

    @Test
    public void malformedUrlSurfacesAsTransportError() throws Exception {
        final WitHostFunction fn = HostCallbacks.httpPostJson();
        final Object[] out = fn.execute(new Object[] {
            ComponentVal.string("not-a-url"),
            ComponentVal.string("{}") });
        final ComponentVal result = (ComponentVal) out[0];
        final ComponentVal err = result.asResult().getErr()
                .orElseThrow(() -> new AssertionError("expected Err"));
        assertThat(err.asString()).startsWith("http transport: ");
    }
}
