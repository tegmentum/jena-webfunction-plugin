package ai.tegmentum.jena.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentType;
import ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor;
import ai.tegmentum.wasmtime4j.wit.WitBool;
import ai.tegmentum.wasmtime4j.wit.WitList;
import ai.tegmentum.wasmtime4j.wit.WitS32;
import ai.tegmentum.wasmtime4j.wit.WitString;
import ai.tegmentum.wasmtime4j.wit.WitValue;
import org.apache.jena.atlas.json.JSON;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static ai.tegmentum.jena.webfunctions.RecordCoercionPolicy.FieldShape;
import static ai.tegmentum.jena.webfunctions.RecordCoercionPolicy.JsonShape;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the bare-arg record-coercion policy that
 * unblocks Gap A in
 * {@code wf-conformance/cases/fulltext_document_corpus.toml} — a bare
 * int at a {@code query-opts}-shaped record must land in the
 * {@code limit} slot and default-synthesize {@code fields: []} and
 * {@code highlight: false}. Mirrors the Oxigraph tests in
 * {@code oxigraph-wf/src/wf_call.rs::bare_arg_synthesis_tests}.
 */
public class RecordCoercionPolicyTest {

    @Test
    public void jsonShapeClassifiesScalars() {
        assertEquals(JsonShape.BOOL, RecordCoercionPolicy.jsonShapeOf(JSON.parseAny("true")));
        assertEquals(JsonShape.NUMBER, RecordCoercionPolicy.jsonShapeOf(JSON.parseAny("10")));
        assertEquals(JsonShape.STRING, RecordCoercionPolicy.jsonShapeOf(JSON.parseAny("\"hi\"")));
        assertEquals(JsonShape.ARRAY, RecordCoercionPolicy.jsonShapeOf(JSON.parseAny("[]")));
        assertEquals(JsonShape.OBJECT, RecordCoercionPolicy.jsonShapeOf(JSON.parseAny("{}")));
    }

    @Test
    public void fieldShapeCategorisesTypes() {
        assertEquals(FieldShape.INT, RecordCoercionPolicy.fieldShapeOf(ComponentType.S32));
        assertEquals(FieldShape.INT, RecordCoercionPolicy.fieldShapeOf(ComponentType.U64));
        assertEquals(FieldShape.FLOAT, RecordCoercionPolicy.fieldShapeOf(ComponentType.F64));
        assertEquals(FieldShape.BOOL, RecordCoercionPolicy.fieldShapeOf(ComponentType.BOOL));
        assertEquals(FieldShape.LIST, RecordCoercionPolicy.fieldShapeOf(ComponentType.LIST));
        assertEquals(FieldShape.OPTION, RecordCoercionPolicy.fieldShapeOf(ComponentType.OPTION));
    }

    @Test
    public void shapeAcceptsIntFieldTakesNumber() {
        assertTrue(RecordCoercionPolicy.shapeAccepts(FieldShape.INT, JsonShape.NUMBER));
        assertTrue(RecordCoercionPolicy.shapeAccepts(FieldShape.FLOAT, JsonShape.NUMBER));
        assertFalse(RecordCoercionPolicy.shapeAccepts(FieldShape.BOOL, JsonShape.NUMBER));
        assertFalse(RecordCoercionPolicy.shapeAccepts(FieldShape.LIST, JsonShape.NUMBER));
    }

    /**
     * Core Gap A regression: bare int against a query-opts-shaped
     * record lands in the {@code limit} slot; {@code fields: []} and
     * {@code highlight: false} synthesize. Uses {@link JenaWasmInstance#placeBareArgIntoRecord}
     * to prove the placement + {@link JenaWasmInstance#defaultValFor}
     * to prove the synthesis — the full jsonToWit path is exercised
     * via the end-to-end conformance suite because that path takes a
     * live ComponentInstance to build the WitRecord.
     */
    @Test
    public void placesLimitAndSynthesizesRemainingQueryOptsFields() {
        final Map<String, ComponentTypeDescriptor> fields = new LinkedHashMap<>();
        fields.put("limit", ComponentTypeDescriptor.s32());
        fields.put("fields", ComponentTypeDescriptor.list(ComponentTypeDescriptor.string()));
        fields.put("highlight", ComponentTypeDescriptor.bool());

        final String placed = JenaWasmInstance.placeBareArgIntoRecord(JSON.parseAny("10"), fields);
        assertEquals("limit", placed);

        // The other two non-optional slots synthesize to their type's zero value.
        final WitValue emptyFields = JenaWasmInstance.defaultValFor(fields.get("fields"));
        assertTrue("fields default should be list, got " + emptyFields.getClass(), emptyFields instanceof WitList);
        assertEquals(0, ((WitList) emptyFields).size());

        final WitValue highlightFalse = JenaWasmInstance.defaultValFor(fields.get("highlight"));
        assertTrue(highlightFalse instanceof WitBool);
        assertFalse(((WitBool) highlightFalse).getValue());
    }

    @Test
    public void ambiguousBareArgFailsWithBothCandidatesNamed() {
        final Map<String, ComponentTypeDescriptor> fields = new LinkedHashMap<>();
        fields.put("min", ComponentTypeDescriptor.s32());
        fields.put("max", ComponentTypeDescriptor.s32());

        final IllegalArgumentException iae = assertThrows(
                IllegalArgumentException.class,
                () -> JenaWasmInstance.placeBareArgIntoRecord(JSON.parseAny("10"), fields));
        final String msg = iae.getMessage();
        assertNotNull(msg);
        assertTrue(msg, msg.contains("ambiguous"));
        assertTrue(msg, msg.contains("min"));
        assertTrue(msg, msg.contains("max"));
    }

    @Test
    public void noMatchingFieldReportsCandidatesConsidered() {
        final Map<String, ComponentTypeDescriptor> fields = new LinkedHashMap<>();
        fields.put("fields", ComponentTypeDescriptor.list(ComponentTypeDescriptor.string()));
        fields.put("highlight", ComponentTypeDescriptor.bool());

        final IllegalArgumentException iae = assertThrows(
                IllegalArgumentException.class,
                () -> JenaWasmInstance.placeBareArgIntoRecord(JSON.parseAny("10"), fields));
        final String msg = iae.getMessage();
        assertTrue(msg, msg.contains("does not match any"));
    }

    @Test
    public void defaultValForScalarTypes() {
        final WitValue s32Zero = JenaWasmInstance.defaultValFor(ComponentTypeDescriptor.s32());
        assertTrue(s32Zero instanceof WitS32);
        assertEquals(0, ((WitS32) s32Zero).getValue());

        final WitValue emptyString = JenaWasmInstance.defaultValFor(ComponentTypeDescriptor.string());
        assertTrue(emptyString instanceof WitString);
        assertEquals("", ((WitString) emptyString).getValue());
    }

    /**
     * Snake-case JSON key {@code include_body} resolves for a kebab
     * WIT field name {@code include-body}. This is the
     * {@code document_federated} / {@code document_managed} callsite's
     * exact shape — user JSON uses snake, WIT declares kebab.
     */
    @Test
    public void snakeJsonKeyResolvesForKebabWitField() {
        final org.apache.jena.atlas.json.JsonObject obj =
                JSON.parseAny("{\"include_body\": true}").getAsObject();
        final org.apache.jena.atlas.json.JsonValue v =
                JenaWasmInstance.lookupRecordField(obj, "include-body");
        assertNotNull("snake fallback should resolve", v);
        assertTrue(v.getAsBoolean().value());
    }

    /**
     * Exact WIT-name match wins over the snake fallback. If a user
     * supplies BOTH spellings, the kebab-spelled key is the one that
     * binds — a caller who wrote the WIT name verbatim gets exactly
     * what they asked for.
     */
    @Test
    public void exactKebabMatchWinsOverSnakeFallback() {
        final org.apache.jena.atlas.json.JsonObject obj =
                JSON.parseAny("{\"include_body\": true, \"include-body\": false}").getAsObject();
        final org.apache.jena.atlas.json.JsonValue v =
                JenaWasmInstance.lookupRecordField(obj, "include-body");
        assertNotNull(v);
        assertFalse("exact kebab match should win", v.getAsBoolean().value());
    }

    @Test
    public void noDashWitNameTakesOnlyExactPath() {
        final org.apache.jena.atlas.json.JsonObject obj =
                JSON.parseAny("{\"limit\": 20}").getAsObject();
        final org.apache.jena.atlas.json.JsonValue v =
                JenaWasmInstance.lookupRecordField(obj, "limit");
        assertNotNull(v);
        assertEquals(20, v.getAsNumber().value().intValue());
    }

    @Test
    public void returnsNullWhenNeitherSpellingPresent() {
        final org.apache.jena.atlas.json.JsonObject obj =
                JSON.parseAny("{\"other\": 1}").getAsObject();
        assertEquals(null, JenaWasmInstance.lookupRecordField(obj, "include-body"));
    }

    /**
     * Missing required {@code list<T>} synthesizes to an empty list —
     * the fix that unblocks {@code document_federated} /
     * {@code document_managed} where user JSON drops
     * {@code "fields": []} entirely and the decoder now supplies it.
     */
    @Test
    public void missingRequiredListSynthesizesEmpty() {
        final WitValue emptyList = JenaWasmInstance.defaultValFor(
                ComponentTypeDescriptor.list(ComponentTypeDescriptor.string()));
        assertTrue("expected WitList, got " + emptyList.getClass(), emptyList instanceof WitList);
        assertEquals(0, ((WitList) emptyList).size());
    }

    /**
     * A record-typed required field (or any other non-defaultable
     * type) still throws — the substrate does not fabricate structured
     * values. Kept to lock down the failure mode so the extended
     * default-synth policy doesn't silently produce garbage for a
     * nested-record slot.
     */
    @Test
    public void missingRequiredNonDefaultableRecordErrors() {
        final java.util.LinkedHashMap<String, ComponentTypeDescriptor> nestedFields =
                new java.util.LinkedHashMap<>();
        nestedFields.put("x", ComponentTypeDescriptor.s32());
        final ComponentTypeDescriptor nestedRecord =
                ComponentTypeDescriptor.record(nestedFields);
        final IllegalArgumentException iae = assertThrows(
                IllegalArgumentException.class,
                () -> JenaWasmInstance.defaultValFor(nestedRecord));
        assertNotNull(iae.getMessage());
        assertTrue(iae.getMessage(), iae.getMessage().contains("no default-synth"));
    }
}
