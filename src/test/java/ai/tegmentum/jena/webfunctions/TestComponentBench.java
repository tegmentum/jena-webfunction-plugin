package ai.tegmentum.jena.webfunctions;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.net.URL;
import java.util.List;

import static org.junit.Assume.assumeTrue;

/**
 * Micro-benchmark for the Jena binding. First-order signal only — hand-rolled
 * {@code nanoTime} rather than JMH. Gate: {@code -Dbench=1}.
 */
public class TestComponentBench {

    private static final String TO_UPPER_WASM =
            System.getProperty("wf.toUpper.wasm",
                    System.getProperty("user.home")
                            + "/git/stardog-webfunction-plugin/src/test/rust/target/wasm32-wasip1/release/to_upper_component.wasm");

    private static final int WARMUP = 500;
    private static final int MEASURED = 5_000;
    private static final int INSTANTIATIONS = 500;

    @Before
    public void gate() {
        assumeTrue("bench off; enable with -Dbench=1",
                "1".equals(System.getProperty("bench")));
        assumeTrue("wasm not built: " + TO_UPPER_WASM,
                new File(TO_UPPER_WASM).exists());
    }

    @After
    public void reset() {
        JenaWasmInstance.resetCache();
    }

    @Test
    public void benchEvaluate() throws Exception {
        final URL url = new File(TO_UPPER_WASM).toURI().toURL();
        final Node arg = NodeFactory.createLiteralString("stardog");

        try (JenaWasmInstance instance = new JenaWasmInstance(url)) {
            // Warm-up.
            for (int i = 0; i < WARMUP; i++) {
                final List<WitValueMarshaller.Row> rows = instance.evaluate(arg);
                if (rows.isEmpty()) throw new IllegalStateException("empty");
            }
            final long start = System.nanoTime();
            for (int i = 0; i < MEASURED; i++) {
                instance.evaluate(arg);
            }
            final long ns = (System.nanoTime() - start) / MEASURED;
            System.out.printf("evaluate: %,10d ns/op (%,10.0f ops/s)%n",
                    ns, 1e9 / ns);
        }
    }

    @Test
    public void benchInstantiation() throws Exception {
        final URL url = new File(TO_UPPER_WASM).toURI().toURL();

        // Warm the cache (compile the component + build engine).
        try (JenaWasmInstance warm = new JenaWasmInstance(url)) {}

        final long start = System.nanoTime();
        for (int i = 0; i < INSTANTIATIONS; i++) {
            new JenaWasmInstance(url).close();
        }
        final long ns = (System.nanoTime() - start) / INSTANTIATIONS;
        System.out.printf("instantiate (cached): %,10d ns/op (%,10.0f ops/s)%n",
                ns, 1e9 / ns);
    }
}
