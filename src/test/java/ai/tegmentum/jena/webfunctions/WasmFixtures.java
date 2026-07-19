package ai.tegmentum.jena.webfunctions;

/**
 * Locators for the shared wasm test fixtures that the four engine
 * bindings (stardog, jena, rdf4j, oxigraph) all load. Retires the
 * pre-consolidation coupling to
 * {@code stardog-webfunction-plugin/src/test/rust/target/…}.
 *
 * <p>Each method resolves a wasm path via:
 * <ol>
 *   <li>An {@code EXAMPLE_*_WASM} environment variable if set and
 *       non-empty, matching the oxigraph-plugin M4 smoke fix</li>
 *   <li>A back-compat {@code -Dwf.*.wasm} JVM system property if
 *       set (preserved so existing local overrides keep working)</li>
 *   <li>The well-known
 *       {@code $HOME/git/webfunctions/target/wasm32-wasip2/release/*}
 *       fallback path — components built by
 *       {@code cargo component build --release --target wasm32-wasip2}
 *       in {@code ~/git/webfunctions}</li>
 * </ol>
 * Tests call {@code Assume.assumeTrue(new File(path).exists(), …)} to
 * skip cleanly when the fixture is absent.
 */
public final class WasmFixtures {

    private WasmFixtures() {
    }

    public static String exampleUppercaseWasm() {
        return resolve(
                "EXAMPLE_UPPERCASE_WASM",
                "wf.toUpper.wasm",
                "example_uppercase_extension.wasm");
    }

    public static String exampleSumAggregateWasm() {
        return resolve(
                "EXAMPLE_SUM_AGGREGATE_WASM",
                "wf.sum.wasm",
                "example_sum_aggregate.wasm");
    }

    public static String exampleMultiVarFilterWasm() {
        return resolve(
                "EXAMPLE_MULTI_VAR_FILTER_WASM",
                "wf.multiVar.wasm",
                "example_multi_var_filter.wasm");
    }

    private static String resolve(final String envVar, final String sysProp, final String basename) {
        final String env = System.getenv(envVar);
        if (env != null && !env.isEmpty()) {
            return env;
        }
        return System.getProperty(sysProp,
                System.getProperty("user.home")
                        + "/git/webfunctions/target/wasm32-wasip2/release/" + basename);
    }
}
