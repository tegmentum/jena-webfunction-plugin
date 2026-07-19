package ai.tegmentum.jena.webfunctions;

import com.sun.net.httpserver.HttpServer;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

/**
 * Verifies wf:call fetches wasm bytes over http:// so the plugin's URL fetch
 * path isn't quietly file:// only. Serves {@code to_upper_component.wasm}
 * from an in-process JDK HTTP server bound to a random loopback port.
 */
public class TestWfCallHttp {

    private static final String TO_UPPER_WASM =
            WasmFixtures.exampleUppercaseWasm();

    private static HttpServer SERVER;
    private static String BASE_URL;

    @BeforeClass
    public static void setUp() throws IOException {
        WebFunctionInit.register();

        final File wasm = new File(TO_UPPER_WASM);
        assumeTrue("to_upper_component.wasm not found at " + wasm
                        + " (override with -Dwf.toUpper.wasm=...)",
                wasm.exists());
        final byte[] wasmBytes = Files.readAllBytes(wasm.toPath());

        SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        SERVER.createContext("/to_upper.wasm", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/wasm");
            exchange.sendResponseHeaders(200, wasmBytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(wasmBytes);
            }
        });
        SERVER.start();
        BASE_URL = "http://127.0.0.1:" + SERVER.getAddress().getPort();
    }

    @AfterClass
    public static void tearDown() {
        if (SERVER != null) {
            SERVER.stop(0);
            SERVER = null;
        }
    }

    @Test
    public void wfCallUppercasesStringViaHttp() {
        final String url = BASE_URL + "/to_upper.wasm";
        final String queryString =
                "PREFIX wf: <" + WfCall.NAMESPACE + ">\n" +
                "SELECT ?result WHERE {\n" +
                "  BIND(wf:call(<" + url + ">, \"stardog\") AS ?result)\n" +
                "}";

        final Query query = QueryFactory.create(queryString);
        try (QueryExecution qe = QueryExecutionFactory.create(query, ModelFactory.createDefaultModel())) {
            final ResultSet rs = qe.execSelect();
            assertThat(rs.hasNext()).isTrue();
            final QuerySolution soln = rs.next();
            assertThat(soln.getLiteral("result").getLexicalForm()).isEqualTo("STARDOG");
            assertThat(rs.hasNext()).isFalse();
        }
    }
}
