package ai.tegmentum.jena.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;
import ai.tegmentum.webassembly4j.api.WitHostFunction;

import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.QuerySolutionMap;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.impl.ResourceImpl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Host callbacks satisfying the v0.3.0 WIT world's {@code host} import interface:
 * {@code stardog:webfunction/host@0.3.0#execute-query} and
 * {@code stardog:webfunction/host@0.3.0#callback-depth}.
 *
 * <p>Same pattern as the RDF4J plugin: {@link ComponentVal} at the linker
 * boundary, not {@code WitValue}. Value marshalling adapts to Jena's
 * {@link Node} type. See {@link CallbackContext} for the ThreadLocal binding
 * mechanism.
 */
public final class HostCallbacks {

    private static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";

    private HostCallbacks() {}

    /** {@code execute-query: func(sparql: string, bindings: list<binding>,
     *  max-rows: option<u32>) -> result<binding-sets, string>}. */
    public static WitHostFunction executeQuery() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: no context bound — WfCall must bind CallbackContext "
                    + "at the top of exec()")) };
            }
            try {
                final String sparql = ((ComponentVal) args[0]).asString();
                final QuerySolutionMap initial = decodeBindings((ComponentVal) args[1]);
                final int rowCap = decodeOptionalU32((ComponentVal) args[2]).orElseGet(ctx::maxRows);

                ctx.enter();
                try {
                    final ResultSet rs = ctx.executeSelect(sparql, initial);
                    return new Object[] { ComponentVal.ok(encodeBindingSets(rs, rowCap)) };
                } finally {
                    ctx.exit();
                }
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    /** {@code callback-depth: func() -> u32}. */
    public static WitHostFunction callbackDepth() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            return new Object[] { ComponentVal.u32(ctx == null ? 0L : (long) ctx.depth()) };
        };
    }

    /** {@code follow-predicate: func(subject: value, predicate: value)
     *  -> result<list<value>, string>}  (v0.3.3). */
    public static WitHostFunction followPredicate() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: no context bound")) };
            }
            try {
                final Node subj = decodeNode((ComponentVal) args[0]);
                final Node pred = decodeNode((ComponentVal) args[1]);
                ctx.enter();
                try {
                    final java.util.List<Node> objs = ctx.followPredicate(subj, pred);
                    final java.util.List<ComponentVal> encoded =
                        new java.util.ArrayList<>(objs.size());
                    for (Node n : objs) encoded.add(encodeNode(n));
                    return new Object[] { ComponentVal.ok(ComponentVal.list(encoded)) };
                } finally {
                    ctx.exit();
                }
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    /** {@code prepare-query: func(sparql: string) -> result<u32, string>}
     *  (v0.3.2). */
    public static WitHostFunction prepareQuery() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: no context bound")) };
            }
            try {
                final String sparql = ((ComponentVal) args[0]).asString();
                return new Object[] { ComponentVal.ok(ComponentVal.u32((long) ctx.prepare(sparql))) };
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    /** {@code run-prepared: func(handle: u32, bindings: list<binding>,
     *  max-rows: option<u32>) -> result<binding-sets, string>}  (v0.3.2). */
    public static WitHostFunction runPrepared() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: no context bound")) };
            }
            try {
                final int handle = (int) ((ComponentVal) args[0]).asU32();
                final QuerySolutionMap initial = decodeBindings((ComponentVal) args[1]);
                final int rowCap = decodeOptionalU32((ComponentVal) args[2]).orElseGet(ctx::maxRows);
                ctx.enter();
                try {
                    final ResultSet rs = ctx.runPrepared(handle, initial);
                    return new Object[] { ComponentVal.ok(encodeBindingSets(rs, rowCap)) };
                } finally {
                    ctx.exit();
                }
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    /** {@code execute-update: func(sparql: string, bindings: list<binding>)
     *  -> result<_, string>}  (v0.3.1). */
    public static WitHostFunction executeUpdate() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: no context bound")) };
            }
            try {
                final String sparql = ((ComponentVal) args[0]).asString();
                final QuerySolutionMap initial = decodeBindings((ComponentVal) args[1]);
                ctx.enter();
                try {
                    ctx.executeUpdate(sparql, initial);
                    return new Object[] { ComponentVal.ok(null) };
                } finally {
                    ctx.exit();
                }
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    // ---- marshalling -------------------------------------------------------

    /**
     * {@code list<binding>} — a ComponentVal list of {@code binding} records
     * with fields ("name", "value"). Jena's initial-binding shape is a
     * QuerySolutionMap keyed by var name.
     */
    private static QuerySolutionMap decodeBindings(final ComponentVal list) {
        final QuerySolutionMap qsm = new QuerySolutionMap();
        for (ComponentVal elem : list.asList()) {
            final Map<String, ComponentVal> fields = elem.asRecord();
            final String name = fields.get("name").asString();
            final Node rdfNode = decodeNode(fields.get("value"));
            qsm.add(name, ModelFactory.createDefaultModel().asRDFNode(rdfNode));
        }
        return qsm;
    }

    /** {@code variant value { iri | literal | bnode }} → Jena Node. */
    private static Node decodeNode(final ComponentVal variant) {
        final ai.tegmentum.wasmtime4j.component.ComponentVariant cv = variant.asVariant();
        final String caseName = cv.getCaseName();
        final ComponentVal payload = cv.getPayload().orElse(null);

        switch (caseName) {
            case "iri":
                return NodeFactory.createURI(payload == null ? "" : payload.asString());
            case "bnode":
                return NodeFactory.createBlankNode(payload == null ? "" : payload.asString());
            case "literal": {
                if (payload == null) {
                    throw new IllegalStateException("wf: literal variant has no payload");
                }
                final Map<String, ComponentVal> fields = payload.asRecord();
                final String label = fields.get("label").asString();
                final String datatype = fields.get("datatype").asString();
                final Optional<ComponentVal> lang = fields.get("lang").asSome();
                if (lang.isPresent()) {
                    return NodeFactory.createLiteralLang(label, lang.get().asString());
                }
                final RDFDatatype dt = TypeMapper.getInstance().getSafeTypeByName(datatype);
                return NodeFactory.createLiteralDT(label, dt);
            }
            default:
                throw new IllegalStateException("wf: unknown value variant case: " + caseName);
        }
    }

    private static Optional<Integer> decodeOptionalU32(final ComponentVal option) {
        return option.asSome().map(v -> (int) v.asU32());
    }

    /**
     * Encode a {@link ResultSet} as {@code record binding-sets { vars: list<string>,
     * rows: list<list<binding>> }}. Materialises up to {@code rowCap} rows.
     */
    private static ComponentVal encodeBindingSets(final ResultSet rs, final int rowCap) {
        final List<String> vars = rs.getResultVars();
        final LinkedHashSet<String> varsSeen = new LinkedHashSet<>(vars);
        final List<ComponentVal> rows = new ArrayList<>();
        int rowsSeen = 0;
        while (rs.hasNext() && rowsSeen < rowCap) {
            final org.apache.jena.query.QuerySolution qs = rs.next();
            final List<ComponentVal> bindings = new ArrayList<>();
            for (String var : vars) {
                final RDFNode node = qs.get(var);
                if (node == null) continue;
                final Map<String, ComponentVal> bindingFields = new LinkedHashMap<>();
                bindingFields.put("name", ComponentVal.string(var));
                bindingFields.put("value", encodeNode(node.asNode()));
                bindings.add(ComponentVal.record(bindingFields));
            }
            rows.add(ComponentVal.list(bindings));
            rowsSeen++;
        }
        final List<ComponentVal> varsVals = new ArrayList<>();
        for (String v : varsSeen) varsVals.add(ComponentVal.string(v));

        final Map<String, ComponentVal> bs = new LinkedHashMap<>();
        bs.put("vars", ComponentVal.list(varsVals));
        bs.put("rows", ComponentVal.list(rows));
        return ComponentVal.record(bs);
    }

    /** Jena Node → {@code variant value { iri | literal | bnode }}. */
    private static ComponentVal encodeNode(final Node n) {
        if (n.isURI()) {
            return ComponentVal.variant("iri", ComponentVal.string(n.getURI()));
        }
        if (n.isBlank()) {
            return ComponentVal.variant("bnode", ComponentVal.string(n.getBlankNodeLabel()));
        }
        if (n.isLiteral()) {
            final String label = n.getLiteralLexicalForm();
            String datatype = n.getLiteralDatatypeURI();
            if (datatype == null || datatype.isEmpty()) datatype = XSD_STRING;
            final Map<String, ComponentVal> fields = new LinkedHashMap<>();
            fields.put("label", ComponentVal.string(label));
            fields.put("datatype", ComponentVal.string(datatype));
            final String lang = n.getLiteralLanguage();
            fields.put("lang", (lang != null && !lang.isEmpty())
                    ? ComponentVal.some(ComponentVal.string(lang))
                    : ComponentVal.none());
            return ComponentVal.variant("literal", ComponentVal.record(fields));
        }
        throw new IllegalArgumentException("wf: unsupported Node kind: " + n);
    }
}
