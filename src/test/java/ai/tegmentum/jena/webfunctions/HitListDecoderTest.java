package ai.tegmentum.jena.webfunctions;

import ai.tegmentum.wasmtime4j.wit.WitList;
import ai.tegmentum.wasmtime4j.wit.WitOption;
import ai.tegmentum.wasmtime4j.wit.WitRecord;
import ai.tegmentum.wasmtime4j.wit.WitString;
import ai.tegmentum.wasmtime4j.wit.WitType;
import ai.tegmentum.wasmtime4j.wit.WitU64;
import ai.tegmentum.wasmtime4j.wit.WitU8;
import ai.tegmentum.wasmtime4j.wit.WitValue;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
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

    // ---------------------------------------------------------------
    // hit.body (option<list<u8>>) projection tests. Mirrors
    // oxigraph-wf/src/wf_call.rs::hit_list_tests body_* cases so the
    // four engines project identical bindings on the same guest
    // output.
    // ---------------------------------------------------------------

    private static final WitType U8_TYPE = WitType.createU8();
    private static final WitType OPT_LIST_U8 = WitType.option(WitType.list(U8_TYPE));

    private static WitList byteList(final byte[] bytes) {
        if (bytes.length == 0) return WitList.empty(U8_TYPE);
        final List<WitValue> elems = new ArrayList<>(bytes.length);
        for (final byte b : bytes) elems.add(WitU8.of(b));
        return WitList.of(elems);
    }

    private static WitOption someByteList(final byte[] bytes) {
        return WitOption.some(OPT_LIST_U8, byteList(bytes));
    }

    @Test
    public void bodyAsciiBytesDecodeAsText() {
        final WitValueMarshaller.TypedScalar ts =
                WitValueMarshaller.flattenOptionalStringOrBytes(
                        someByteList("hello".getBytes(StandardCharsets.US_ASCII)));
        assertNotNull(ts);
        assertEquals("ASCII bytes must UTF-8 decode",
                WitValueMarshaller.TypedScalar.TEXT, ts.kind);
        assertEquals("hello", ts.text);
    }

    @Test
    public void bodyMultibyteUtf8BytesDecodeAsText() {
        // "héllo, 世界" — 2-byte é and 3-byte CJK glyphs exercise the
        // UTF-8-first path across code-point widths.
        final String payload = "héllo, 世界";
        final WitValueMarshaller.TypedScalar ts =
                WitValueMarshaller.flattenOptionalStringOrBytes(
                        someByteList(payload.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(ts);
        assertEquals(WitValueMarshaller.TypedScalar.TEXT, ts.kind);
        assertEquals(payload, ts.text);
    }

    @Test
    public void bodyInvalidUtf8BytesFallBackToBase64Binary() {
        // 0xff, 0xfe, 0xfd — always invalid as a UTF-8 sequence.
        final byte[] raw = new byte[]{(byte) 0xff, (byte) 0xfe, (byte) 0xfd};
        final WitValueMarshaller.TypedScalar ts =
                WitValueMarshaller.flattenOptionalStringOrBytes(
                        someByteList(raw));
        assertNotNull(ts);
        assertEquals("Non-UTF-8 bytes must NOT decode as text",
                WitValueMarshaller.TypedScalar.BYTES, ts.kind);
        assertArrayEquals(raw, ts.bytes);
        // Cross-check the base64 rendering the projection will emit
        // ("//79" for these three bytes).
        assertEquals("//79", Base64.getEncoder().encodeToString(ts.bytes));
    }

    @Test
    public void bodyNoneDropsBinding() {
        final WitOption none = WitOption.none(OPT_LIST_U8);
        assertNull("None must return null so caller drops the binding",
                WitValueMarshaller.flattenOptionalStringOrBytes(none));
    }

    @Test
    public void bareByteListWithoutOptionWrapperAlsoProjects() {
        final WitValueMarshaller.TypedScalar ts =
                WitValueMarshaller.flattenOptionalStringOrBytes(
                        byteList("world".getBytes(StandardCharsets.US_ASCII)));
        assertNotNull(ts);
        assertEquals(WitValueMarshaller.TypedScalar.TEXT, ts.kind);
        assertEquals("world", ts.text);
    }

    @Test
    public void bareStringStillFlattensAsText() {
        // Regression guard: `flatten_optional_string`'s original
        // string-branch semantics must survive.
        final WitValueMarshaller.TypedScalar ts =
                WitValueMarshaller.flattenOptionalStringOrBytes(unchecked("hi"));
        assertNotNull(ts);
        assertEquals(WitValueMarshaller.TypedScalar.TEXT, ts.kind);
        assertEquals("hi", ts.text);
    }

    private static WitString unchecked(final String s) {
        try {
            return WitString.of(s);
        } catch (ai.tegmentum.wasmtime4j.exception.ValidationException e) {
            throw new AssertionError("bad fixture string: " + s, e);
        }
    }
}
