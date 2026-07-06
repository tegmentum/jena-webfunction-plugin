package ai.tegmentum.jena.webfunctions;

import ai.tegmentum.jena.FusekiContainer;

import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

/**
 * Integration test that boots an Apache Jena Fuseki container via
 * Testcontainers, mounts the shaded plugin JAR into {@code /fuseki/extra} so
 * Fuseki's {@code JenaSystem.init()} picks up {@code WebFunctionInit} via the
 * {@code JenaSubsystemLifecycle} SPI, and drives a {@code wf:call} SPARQL query
 * against the running server over HTTP.
 *
 * <p>Skipped when Docker is unavailable, the shaded JAR hasn't been built, or
 * the wasm component isn't present.
 */
public class FusekiWasmIT {

    private static final String DATASET = "ds";
    private static final String PLUGIN_JAR = System.getProperty("wf.plugin.jar",
            "target/tegmentum-jena-webfunction-0.1.0-SNAPSHOT.jar");
    private static final String WASM_PATH = System.getProperty("wf.toUpper.wasm",
            System.getProperty("user.home")
                    + "/git/stardog-webfunction-plugin/src/test/rust/target/wasm32-wasip1/release/to_upper_component.wasm");

    private static FusekiContainer CONTAINER;
    private static String WASM_URL;

    @BeforeClass
    public static void bootContainer() {
        assumeTrue("Docker not available", isDockerAvailable());
        assumeTrue("plugin JAR not built: " + PLUGIN_JAR + " (run `mvn package`)",
                new File(PLUGIN_JAR).exists());
        assumeTrue("wasm not built: " + WASM_PATH,
                new File(WASM_PATH).exists());
        // Fuseki runs Linux/amd64; the plugin JAR must include the matching
        // wasmtime4j native. wasmtime4j-native ships per-platform classifier
        // jars; when a dev builds locally on Darwin/aarch64, only the darwin
        // native gets shaded in. Skip cleanly instead of failing at runtime
        // inside the container.
        assumeTrue("plugin JAR missing linux native "
                        + "(natives/linux-x86_64/libwasmtime4j.so)",
                jarContains(PLUGIN_JAR, "natives/linux-x86_64/libwasmtime4j.so"));

        CONTAINER = new FusekiContainer()
                .withDataset(DATASET)
                .withPluginJar(PLUGIN_JAR);
        WASM_URL = CONTAINER.withWasm(WASM_PATH, "/opt/wasm/to_upper_component.wasm");
        CONTAINER.start();
    }

    @AfterClass
    public static void tearDown() {
        if (CONTAINER != null) CONTAINER.stop();
    }

    @Test
    public void wfCallInsideContainerUppercases() {
        final String query =
                "PREFIX wf: <" + WfCall.NAMESPACE + ">\n" +
                "SELECT ?result WHERE {\n" +
                "  BIND(wf:call(<" + WASM_URL + ">, \"stardog\") AS ?result)\n" +
                "}";

        try (QueryExecution qe = QueryExecutionHTTP.newBuilder()
                .endpoint(CONTAINER.getSparqlEndpoint())
                .query(query)
                .build()) {
            final ResultSet rs = qe.execSelect();
            assertThat(rs.hasNext()).isTrue();
            final QuerySolution soln = rs.next();
            assertThat(soln.getLiteral("result").getLexicalForm()).isEqualTo("STARDOG");
            assertThat(rs.hasNext()).isFalse();
        }
    }

    private static boolean isDockerAvailable() {
        try {
            org.testcontainers.DockerClientFactory.instance().client();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean jarContains(final String jarPath, final String entry) {
        try (JarFile jf = new JarFile(jarPath)) {
            final Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                if (entries.nextElement().getName().equals(entry)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
