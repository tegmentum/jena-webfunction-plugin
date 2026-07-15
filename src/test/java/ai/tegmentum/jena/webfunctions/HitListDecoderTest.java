package ai.tegmentum.jena.webfunctions;

import ai.tegmentum.wasmtime4j.wit.WitOption;
import ai.tegmentum.wasmtime4j.wit.WitRecord;
import ai.tegmentum.wasmtime4j.wit.WitString;
import ai.tegmentum.wasmtime4j.wit.WitType;
import ai.tegmentum.wasmtime4j.wit.WitU64;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

/**
 * Regression tests for
 * {@link WitValueMarshaller#flattenHitDoc(ai.tegmentum.wasmtime4j.wit.WitValue)}
 * — the shape-tolerant projector that lets the substrate accept both
 * the legacy wf_fulltext {@code hit.doc: string} shape and the
 * wf_document v1.0+ {@code hit.doc: doc-ref { id, revision }} record
 * shape without a WIT-world change.
 *
 * <p>Mirrors the equivalent Rust tests in
 * {@code oxigraph-wf/src/wf_call.rs::hit_list_tests} and
 * {@code qlever-wf-runtime/src/lib.rs::hit_list_tests}. See
 * {@code wf-conformance/cases/document_federated.toml} for the
 * end-to-end case this fix unblocks.
 */
public class HitListDecoderTest {

    private static final WitType OPT_U64 = WitType.option(WitType.createU64());

    @Test
    public void legacyStringShapePreservesRevisionNull() {
        final WitValueMarshaller.DocRefFlat flat =
                WitValueMarshaller.flattenHitDoc(unchecked("sirix://docs/manuals/1"));
        assertNotNull(flat);
        assertEquals("sirix://docs/manuals/1", flat.id);
        assertNull("legacy string doc has no revision", flat.revision);
    }

    @Test
    public void docRefRecordWithNoneRevisionFlattensIdAndOmitsRevision() {
        final WitRecord doc = WitRecord.builder()
                .field("id", unchecked("sirix://docs/manuals/42"))
                .field("revision", WitOption.none(OPT_U64))
                .build();
        final WitValueMarshaller.DocRefFlat flat =
                WitValueMarshaller.flattenHitDoc(doc);
        assertNotNull(flat);
        assertEquals("sirix://docs/manuals/42", flat.id);
        assertNull("None revision must drop", flat.revision);
    }

    @Test
    public void docRefRecordWithSomeRevisionCarriesU64() {
        final WitRecord doc = WitRecord.builder()
                .field("id", unchecked("sirix://docs/manuals/42"))
                .field("revision",
                        WitOption.some(OPT_U64, WitU64.of(7L)))
                .build();
        final WitValueMarshaller.DocRefFlat flat =
                WitValueMarshaller.flattenHitDoc(doc);
        assertNotNull(flat);
        assertEquals("sirix://docs/manuals/42", flat.id);
        assertEquals("Some(7) revision surfaces as 7",
                Long.valueOf(7L), flat.revision);
    }

    @Test
    public void docRefRecordMissingIdIsError() {
        final WitRecord doc = WitRecord.builder()
                .field("revision", WitOption.none(OPT_U64))
                .build();
        assertThrows(
                "doc-ref without `id` must fail — no honest binding to project",
                IllegalArgumentException.class,
                () -> WitValueMarshaller.flattenHitDoc(doc));
    }

    private static WitString unchecked(final String s) {
        try {
            return WitString.of(s);
        } catch (ai.tegmentum.wasmtime4j.exception.ValidationException e) {
            throw new AssertionError("bad fixture string: " + s, e);
        }
    }
}
