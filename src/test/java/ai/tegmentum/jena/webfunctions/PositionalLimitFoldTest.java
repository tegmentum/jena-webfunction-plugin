package ai.tegmentum.jena.webfunctions;

import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.atlas.json.JSON;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for
 * {@link JenaWasmInstance#mergePositionalLimitIntoOptsJson(String, long)},
 * the pure JSON-merge core of the wf:partial explicit-callsite 5-vs-6
 * arg fold. Covers the {@code document_federated} /
 * {@code document_managed} callsite shape:
 *
 * <pre>
 *   wf:partial(&lt;WASM&gt;, MANTICORE, SIRIX, index, query,
 *              20, '{"include_body":true,"highlight":false}')
 * </pre>
 *
 * where the guest declares {@code search(a, b, c, d, opts)} with
 * {@code limit} living inside the {@code opts} record. Mirrors
 * {@code oxigraph-wf/src/wf_call.rs::positional_limit_fold_tests} so
 * both engines coerce identical callsites into identical invocations.
 */
public class PositionalLimitFoldTest {

    @Test
    public void mergesLimitIntoOptsObject() {
        final String opts = "{\"include_body\":true,\"highlight\":false}";
        final String merged = JenaWasmInstance.mergePositionalLimitIntoOptsJson(opts, 20L);
        assertNotNullMsg(merged, "merged");
        final JsonObject obj = JSON.parseAny(merged).getAsObject();
        assertEquals(20L, obj.get("limit").getAsNumber().value().longValue());
        assertTrue(obj.get("include_body").getAsBoolean().value());
        assertFalse(obj.get("highlight").getAsBoolean().value());
    }

    @Test
    public void mergesIntoEmptyObject() {
        final String merged = JenaWasmInstance.mergePositionalLimitIntoOptsJson("{}", 10L);
        assertNotNullMsg(merged, "merged");
        final JsonObject obj = JSON.parseAny(merged).getAsObject();
        assertEquals(10L, obj.get("limit").getAsNumber().value().longValue());
    }

    @Test
    public void explicitOptsLimitWinsOverPositional() {
        // Matches the URL-sugar path's `or_insert_with` semantics: an
        // explicit "limit":50 in the opts blob is NOT overwritten by
        // the positional 20. Users who spell both get the explicit
        // one, same as they would through the URL-sugar path.
        final String opts = "{\"limit\":50,\"include_body\":true}";
        final String merged = JenaWasmInstance.mergePositionalLimitIntoOptsJson(opts, 20L);
        assertNotNullMsg(merged, "merged");
        final JsonObject obj = JSON.parseAny(merged).getAsObject();
        assertEquals(50L, obj.get("limit").getAsNumber().value().longValue());
    }

    @Test
    public void returnsNullForNonObjectJson() {
        // A JSON array / scalar isn't a valid opts blob — the merge
        // must decline so the honest arg-count-mismatch error surfaces
        // downstream instead of a misleading fold.
        assertNull(JenaWasmInstance.mergePositionalLimitIntoOptsJson("[1,2,3]", 20L));
        assertNull(JenaWasmInstance.mergePositionalLimitIntoOptsJson("42", 20L));
        assertNull(JenaWasmInstance.mergePositionalLimitIntoOptsJson("\"str\"", 20L));
    }

    @Test
    public void returnsNullForMalformedJson() {
        assertNull(JenaWasmInstance.mergePositionalLimitIntoOptsJson("{not json}", 20L));
        assertNull(JenaWasmInstance.mergePositionalLimitIntoOptsJson("", 20L));
    }

    private static void assertNotNullMsg(final Object o, final String name) {
        if (o == null) {
            throw new AssertionError(name + " must not be null");
        }
    }
}
