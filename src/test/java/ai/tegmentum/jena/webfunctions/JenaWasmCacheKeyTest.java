package ai.tegmentum.jena.webfunctions;

import ai.tegmentum.webassembly4j.api.Component;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

/**
 * Regression tests for {@link JenaWasmInstance#cacheKeyFor(URL)}, the
 * content-aware wasm cache-key derivation. Mirrors
 * {@code oxigraph-wf/src/wf_call.rs::cache_key_tests} and
 * {@code qlever-wf-runtime}'s cache-key regression suite.
 *
 * <p>The bug the derivation fixes: prior to this method's introduction
 * {@code COMPONENT_CACHE} was keyed by the raw {@link URL}, so rebuilding
 * a {@code file://} guest wasm on disk didn't invalidate the compiled
 * cache. Long-running JVM hosts (Fuseki, dev servers) kept returning
 * yesterday's bytes until the process itself restarted. Folding source
 * mtime into the key for {@code file://} URLs closes that hole; HTTP and
 * IPFS URLs keep the bare form (HTTP because v0.1 doesn't yet honour
 * Cache-Control/ETag anyway, IPFS because it's content-addressed).
 */
public class JenaWasmCacheKeyTest {

    private static final String TO_UPPER_WASM =
            WasmFixtures.exampleUppercaseWasm();

    @BeforeClass
    public static void registerFunctions() {
        WebFunctionInit.register();
    }

    @Before
    @After
    public void resetCache() {
        // Drop cached components + shared engine so ordering can't
        // leak state across tests here or in surrounding classes.
        JenaWasmInstance.resetCache();
    }

    @Test
    public void httpUrlKeyIsBareUrl() throws Exception {
        final URL u = new URL("https://example.com/guest.wasm");
        assertThat(JenaWasmInstance.cacheKeyFor(u)).isEqualTo(u.toString());
    }

    @Test
    public void ipfsUrlKeyIsBareUrl() throws Exception {
        // ipfs:// requires the protocol handler to be registered — the
        // handler is a runtime detail we don't want to boot here; use
        // http:// to stand in for "any non-file scheme" and check the
        // negative case (no mtime fragment).
        final URL u = new URL("https://ipfs.io/ipfs/QmHash/mod.wasm");
        final String key = JenaWasmInstance.cacheKeyFor(u);
        assertThat(key).isEqualTo(u.toString());
        assertThat(key).doesNotContain("#mtime=");
    }

    @Test
    public void missingFileUrlFallsBackToBareUrl() throws Exception {
        // File doesn't exist: no mtime to sample. Return the URL and
        // let the fetch path emit the honest error rather than
        // miscaching an empty payload.
        final URL u = new URL("file:///no/such/path/jena-wf-cache-key-test.wasm");
        assertThat(JenaWasmInstance.cacheKeyFor(u)).isEqualTo(u.toString());
    }

    @Test
    public void fileUrlKeyFoldsInMtimeAndFlipsOnRewrite() throws Exception {
        final Path dir = Files.createTempDirectory("jena-wf-cache-key");
        try {
            final Path wasm = dir.resolve("guest.wasm");
            Files.write(wasm, "first".getBytes());
            final URL u = wasm.toUri().toURL();

            final String k1 = JenaWasmInstance.cacheKeyFor(u);
            assertThat(k1).contains("#mtime=");

            // Some filesystems have 1s mtime granularity; retry with
            // fresh writes until the stamp advances (or bail after 2s).
            Thread.sleep(20);
            Files.write(wasm, "second-payload-different-length".getBytes());
            String k2 = JenaWasmInstance.cacheKeyFor(u);
            final long deadline = System.currentTimeMillis() + 2_000;
            int attempt = 0;
            while (k2.equals(k1) && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
                Files.write(wasm, ("payload-" + (++attempt)).getBytes());
                k2 = JenaWasmInstance.cacheKeyFor(u);
            }
            assertThat(k2)
                    .as("rewriting the source must produce a new cache key")
                    .isNotEqualTo(k1);
        } finally {
            deleteRecursively(dir);
        }
    }

    /**
     * End-to-end: a real guest wasm at a {@code file://} URL, loaded
     * twice through {@link JenaWasmInstance}. Between the two loads
     * the file's mtime is bumped (belt-and-braces: contents also
     * change). Assert that the second load produced a different
     * {@link Component} instance, i.e. the cache slot flipped.
     *
     * <p>Before the fix both loads returned the same cached
     * {@code Component} because {@code COMPONENT_CACHE} keyed by the
     * bare URL — a long-running JVM host (Fuseki, servlet) would
     * silently serve stale bytes for the process's lifetime.
     */
    @Test
    public void componentCacheFlipsOnFileRewrite() throws Exception {
        final File srcWasm = new File(TO_UPPER_WASM);
        assumeTrue("to_upper_component.wasm not found at " + srcWasm, srcWasm.exists());

        final Path dir = Files.createTempDirectory("jena-wf-component-cache-flip");
        try {
            final Path staging = dir.resolve("guest.wasm");
            Files.copy(srcWasm.toPath(), staging, StandardCopyOption.REPLACE_EXISTING);
            final URL url = staging.toUri().toURL();

            // First load — populates COMPONENT_CACHE under key1.
            final String key1;
            try (JenaWasmInstance ignored = new JenaWasmInstance(url)) {
                key1 = JenaWasmInstance.cacheKeyFor(url);
                assertThat(componentCache()).containsKey(key1);
            }

            // Bump mtime — retry-until-stamp-advances to tolerate
            // coarse (1s) filesystems.
            Thread.sleep(20);
            Files.copy(srcWasm.toPath(), staging, StandardCopyOption.REPLACE_EXISTING);
            String key2 = JenaWasmInstance.cacheKeyFor(url);
            final long deadline = System.currentTimeMillis() + 2_000;
            while (key2.equals(key1) && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
                // Touch mtime explicitly via a rewrite; the content is
                // still valid wasm.
                Files.copy(srcWasm.toPath(), staging, StandardCopyOption.REPLACE_EXISTING);
                key2 = JenaWasmInstance.cacheKeyFor(url);
            }
            assertThat(key2).isNotEqualTo(key1);

            // Second load — must produce a NEW cache slot.
            try (JenaWasmInstance ignored = new JenaWasmInstance(url)) {
                assertThat(componentCache()).containsKey(key2);
                final Component first = componentCache().get(key1);
                final Component second = componentCache().get(key2);
                assertThat(second).isNotSameAs(first);
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, Component> componentCache() throws Exception {
        final java.lang.reflect.Field f =
                JenaWasmInstance.class.getDeclaredField("COMPONENT_CACHE");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, Component>) f.get(null);
    }

    private static void deleteRecursively(final Path dir) {
        try {
            if (!Files.exists(dir)) return;
            Files.walk(dir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                            // best-effort cleanup
                        }
                    });
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }
}
