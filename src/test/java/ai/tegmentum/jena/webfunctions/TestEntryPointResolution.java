package ai.tegmentum.jena.webfunctions;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.net.URL;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assume.assumeTrue;

/**
 * Coverage for the guest export-name auto-detect that mirrors
 * {@code oxigraph-wf/src/wf_call.rs::resolve_entry_point}. The 4-step
 * resolution order is exercised via both a pure static helper (so the
 * ambiguous case doesn't need a matching fixture) and end-to-end against
 * real guests whose WIT worlds honestly export different names.
 *
 * <ul>
 *   <li>{@code to_upper_component.wasm} — legacy stardog:webfunction world,
 *       exports {@code evaluate}. Resolver must still pick {@code evaluate}
 *       — this is the backwards-compat guarantee.</li>
 *   <li>{@code wf_fulltext.wasm} — wf:fulltext@0.1.0 world, exports
 *       {@code search}. Resolver must pick {@code search} via the
 *       single-top-level-function fallback.</li>
 *   <li>Multi-export ambiguous — no {@code evaluate}, multiple candidates.
 *       Resolver must throw with the export list in the message.</li>
 * </ul>
 */
public class TestEntryPointResolution {

    private static final String TO_UPPER_WASM =
            System.getProperty("wf.toUpper.wasm",
                    System.getProperty("user.home")
                            + "/git/stardog-webfunction-plugin/src/test/rust/target/wasm32-wasip1/release/to_upper_component.wasm");

    private static final String WF_FULLTEXT_WASM =
            System.getProperty("wf.fulltext.wasm",
                    System.getProperty("user.home")
                            + "/git/webfunctions/target/wasm32-wasip1/release/wf_fulltext.wasm");

    @BeforeClass
    public static void registerFunctions() {
        WebFunctionInit.register();
    }

    @Before
    @After
    public void resetCache() {
        // Purge the shared engine + component + entry-point caches so
        // ordering between tests can't leak resolved names or engines.
        JenaWasmInstance.resetCache();
    }

    /**
     * Backwards-compat: a guest that exports {@code evaluate} (every
     * stardog:webfunction guest to date) must resolve to {@code evaluate}
     * even when it also exports {@code doc}/{@code aggregate-*}. Step 2 of
     * the resolution order.
     */
    @Test
    public void resolvesEvaluateOnLegacyGuest() throws Exception {
        final File wasm = new File(TO_UPPER_WASM);
        assumeTrue("to_upper_component.wasm not found at " + wasm, wasm.exists());
        final URL url = wasm.toURI().toURL();

        try (JenaWasmInstance instance = new JenaWasmInstance(url)) {
            assertThat(instance.resolveEntryPoint(null)).isEqualTo("evaluate");
        }
    }

    /**
     * Post-well-known-exports-migration, wf_fulltext exports
     * {@code tegmentum:webfunction/search@0.1.0} (the named-interface
     * search surface) alongside its {@code insert-batch} /
     * {@code delete-batch} bare admin exports. The resolver's
     * WELL_KNOWN_INTERFACES probe wins over the pure-static bare-name
     * heuristic, so we get the qualified interface#function form.
     * Pre-migration wf_fulltext (which exported bare {@code search})
     * would resolve to the bare name via the static resolver's step 3;
     * this test covers the post-migration path.
     */
    @Test
    public void resolvesSearchOnWfFulltext() throws Exception {
        final File wasm = new File(WF_FULLTEXT_WASM);
        assumeTrue("wf_fulltext.wasm not found at " + wasm, wasm.exists());
        final URL url = wasm.toURI().toURL();

        try (JenaWasmInstance instance = new JenaWasmInstance(url)) {
            assertThat(instance.resolveEntryPoint(null))
                    .isEqualTo("tegmentum:webfunction/search@0.1.0#search");
        }
    }

    /**
     * Direct coverage of the well-known-name heuristic against the
     * exact wf_fulltext export set. Exercised via the pure static
     * helper so the test doesn't depend on the guest wasm being on
     * disk.
     */
    @Test
    public void staticHelperPicksSearchOverAdminExports() {
        final List<String> exports = List.of("search", "insert-batch", "delete-batch");
        assertThat(JenaWasmInstance.resolveEntryPoint(null, exports, "file:///wf_fulltext.wasm"))
                .isEqualTo("search");
    }

    /**
     * Well-known list order matters: {@code search} beats
     * {@code execute} when both are present.
     */
    @Test
    public void staticHelperHonoursWellKnownOrder() {
        final List<String> exports = List.of("execute", "search");
        assertThat(JenaWasmInstance.resolveEntryPoint(null, exports, "file:///demo.wasm"))
                .isEqualTo("search");
    }

    /**
     * Explicit override wins over auto-detect. Step 1 of the resolution
     * order.
     */
    @Test
    public void callerOverrideBeatsAutoDetect() throws Exception {
        final File wasm = new File(TO_UPPER_WASM);
        assumeTrue("to_upper_component.wasm not found at " + wasm, wasm.exists());
        final URL url = wasm.toURI().toURL();

        try (JenaWasmInstance instance = new JenaWasmInstance(url)) {
            // Legacy guest exports `evaluate` but caller asks for `doc` —
            // override wins even though it's a valid other export.
            assertThat(instance.resolveEntryPoint("doc")).isEqualTo("doc");
        }
    }

    /**
     * Multi-export ambiguous case → thrown IllegalStateException whose
     * message enumerates the visible exports. Exercised via the static
     * helper so this test doesn't depend on a bespoke fixture.
     */
    @Test
    public void ambiguousMultiExportListsCandidatesInError() {
        final List<String> exports = List.of("first", "second", "third");
        assertThatThrownBy(() ->
                JenaWasmInstance.resolveEntryPoint(null, exports, "file:///demo.wasm"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("multiple function exports")
                .hasMessageContaining("first")
                .hasMessageContaining("second")
                .hasMessageContaining("third")
                .hasMessageContaining("no `evaluate`");
    }

    /**
     * The static helper prefers {@code evaluate} whenever it's present in
     * the export list — even alongside a raft of other functions. Direct
     * coverage of step 2.
     */
    @Test
    public void staticHelperPrefersEvaluate() {
        final List<String> exports = List.of("evaluate", "doc", "aggregate-step", "aggregate-finish");
        assertThat(JenaWasmInstance.resolveEntryPoint(null, exports, "file:///demo.wasm"))
                .isEqualTo("evaluate");
    }

    /**
     * A component with no top-level function exports is an error — this
     * catches the pathological case of a component that exposes only
     * interface-scoped exports and no top-level function.
     */
    @Test
    public void noFunctionExportsIsAnError() {
        assertThatThrownBy(() ->
                JenaWasmInstance.resolveEntryPoint(null, List.of(), "file:///empty.wasm"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no function exports");
    }
}
