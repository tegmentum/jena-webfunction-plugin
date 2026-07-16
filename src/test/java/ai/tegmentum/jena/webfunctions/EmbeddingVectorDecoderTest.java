package ai.tegmentum.jena.webfunctions;

import ai.tegmentum.wasmtime4j.wit.WitFloat32;
import ai.tegmentum.wasmtime4j.wit.WitList;
import ai.tegmentum.wasmtime4j.wit.WitString;
import ai.tegmentum.wasmtime4j.wit.WitType;
import ai.tegmentum.wasmtime4j.wit.WitValue;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Regression coverage for
 * {@link WitValueMarshaller#bindingSetsFromWit(WitValue, String)} — the
 * wf_sagegraph {@code embed} return decode path. The guest hands back a
 * bare {@code list<float32>}; the substrate flattens it into a
 * single-row binding-sets that projects {@code ?node} (from the
 * decode-time input-node-iri hint) and {@code ?embedding} (JSON string).
 *
 * <p>Mirrors {@code oxigraph-wf/src/wf_call.rs::embedding_vector_tests}
 * and {@code qlever-wf-runtime/src/lib.rs::embedding_vector_*}.
 */
public class EmbeddingVectorDecoderTest {

    @Test
    public void floatListProjectsNodeAndEmbeddingColumns() {
        final WitList list = floatList(4.0f, 3.5f, 4.0f, 0.0f);
        final List<WitValueMarshaller.Row> rows =
                WitValueMarshaller.bindingSetsFromWit(list, "http://example.com/alice");
        assertEquals(1, rows.size());
        final WitValueMarshaller.Row row = rows.get(0);
        assertEquals(Arrays.asList("node", "embedding"), row.vars);
        final Node node = row.values.get(0);
        assertNotNull(node);
        assertTrue(node.isURI());
        assertEquals("http://example.com/alice", node.getURI());
        final Node embedding = row.values.get(1);
        assertNotNull(embedding);
        assertTrue(embedding.isLiteral());
        assertEquals("[4, 3.5, 4, 0]", embedding.getLiteralLexicalForm());
        assertEquals(XSDDatatype.XSDstring.getURI(),
                embedding.getLiteralDatatypeURI());
    }

    @Test
    public void floatListWithoutNodeHintEmitsEmbeddingOnly() {
        final WitList list = floatList(1.0f, -0.25f);
        final List<WitValueMarshaller.Row> rows =
                WitValueMarshaller.bindingSetsFromWit(list, null);
        assertEquals(1, rows.size());
        final WitValueMarshaller.Row row = rows.get(0);
        assertEquals(java.util.Collections.singletonList("embedding"), row.vars);
        // Java `Float.toString(-0.25f)` is `-0.25`; matches the Rust
        // `format!("{}", -0.25f32)` output byte-for-byte.
        assertEquals("[1, -0.25]", row.values.get(0).getLiteralLexicalForm());
    }

    @Test
    public void nonUriHintDropsNodeColumn() {
        // An input-node-iri hint that isn't URI-shaped must not
        // synthesize a bad NamedNode; the decoder silently drops
        // `?node` in that case.
        final WitList list = floatList(0.5f);
        final List<WitValueMarshaller.Row> rows =
                WitValueMarshaller.bindingSetsFromWit(list, "not an iri");
        assertEquals(1, rows.size());
        assertEquals(java.util.Collections.singletonList("embedding"),
                rows.get(0).vars);
    }

    @Test
    public void emptyListRoutesThroughHitDecoder() {
        // Empty list defaults to hit-list decode → empty rows.
        // Preserves the historical contract for callers that never
        // emit a wf_sagegraph return.
        final WitList list = WitList.empty(WitType.createFloat32());
        final List<WitValueMarshaller.Row> rows =
                WitValueMarshaller.bindingSetsFromWit(list, null);
        assertTrue("empty list → empty binding-set", rows.isEmpty());
    }

    // NOTE: WitList.of enforces homogeneous element types at
    // construction time, so a `list<float32>` guest return can never
    // contain a non-f32 element by the time it reaches the decoder.
    // The mixed-list guard in `floatListToRows` therefore exists as a
    // defense-in-depth check against future WIT type-system changes;
    // no unit test is expressible today.

    @Test
    public void integerValuedComponentsPrintWithoutTrailingDotZero() {
        // Byte-parity target with the Rust substrate.
        final String s = WitValueMarshaller.embeddingVectorToJson(
                new float[]{4.0f, 3.5f, 4.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f});
        assertEquals("[4, 3.5, 4, 0, 0, 0, 0, 0]", s);
    }

    private static WitList floatList(final float... xs) {
        final List<WitValue> elems = new ArrayList<>(xs.length);
        for (float x : xs) elems.add(WitFloat32.of(x));
        return WitList.of(elems);
    }
}
