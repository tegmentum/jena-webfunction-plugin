package ai.tegmentum.jena.webfunctions;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.QueryException;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpService;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingBuilder;
import org.apache.jena.sparql.engine.iterator.QueryIterNullIterator;
import org.apache.jena.sparql.engine.iterator.QueryIterPlainWrapper;
import org.apache.jena.sparql.service.single.ChainingServiceExecutor;
import org.apache.jena.sparql.service.single.ServiceExecutor;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles the {@code SERVICE <wf:call> { ... }} form: a BGP-shaped envelope
 * that lifts the wasm's {@code binding-sets} return into first-class SPARQL
 * variables. Complements {@link WfCallService} (which matches SERVICE URIs
 * that <em>are</em> the wasm URL directly) — this variant keeps the wasm
 * URL, positional args, and output-column mapping inside the SERVICE body
 * as triples, matching the shape shipped by the oxigraph-wf sibling.
 *
 * <p>Recognised triples inside the SERVICE body (predicates in the
 * {@code http://tegmentum.ai/ns/webfunction/} namespace; subjects are
 * ignored — they exist only to satisfy Turtle grammar):
 * <ul>
 *   <li>{@code wf:wasm <url>} — the wasm component URL (constant IRI).
 *   <li>{@code wf:arg X} (repeatable, in document order) — positional
 *       arguments to {@code evaluate(list<value>)}. {@code X} may be a
 *       variable resolved from the outer binding.
 *   <li>{@code wf:<colname> ?var} — projects the wasm's binding-set column
 *       named {@code <colname>} onto {@code ?var} per output row.
 * </ul>
 *
 * <p>Execution is per outer input binding: for each row that flows in, args
 * are resolved, the wasm is invoked, and each returned binding-set row
 * becomes a new solution extending the input binding. Skipping an input
 * whose {@code wf:arg} references an unbound variable preserves join
 * semantics (nothing to match against, no output).
 */
public final class WfCallServiceExecutor implements ChainingServiceExecutor {

    /** SERVICE IRI that steers into this executor; anything else chains through. */
    public static final String SERVICE_IRI = "wf:call";

    private static final String WF_NS = "http://tegmentum.ai/ns/webfunction/";
    private static final String WF_WASM = WF_NS + "wasm";
    private static final String WF_ARG = WF_NS + "arg";

    @Override
    public QueryIterator createExecution(
            final OpService opService,
            final OpService original,
            final Binding binding,
            final ExecutionContext ctx,
            final ServiceExecutor next) {
        final Node svcIri = opService.getService();
        if (!svcIri.isURI() || !SERVICE_IRI.equals(svcIri.getURI())) {
            return next.createExecution(opService, original, binding, ctx);
        }

        final Envelope env = parseEnvelope(opService.getSubOp());

        // Wasm URL must be a constant IRI or a bound variable that resolves
        // to one. Anything else — including a variable that's unbound in
        // this outer binding — yields no rows for this input, which is the
        // correct join semantics.
        final Node wasmNode = resolveNode(env.wasmNode, binding);
        if (wasmNode == null) return QueryIterNullIterator.create(ctx);
        final URL wasmUrl;
        try {
            wasmUrl = new URL(nodeAsUrlString(wasmNode));
        } catch (MalformedURLException e) {
            throw new QueryException("wf:call SERVICE: bad wasm URL: " + wasmNode, e);
        }

        // Resolve positional args against the outer binding. Any unbound
        // variable argument short-circuits — the wasm cannot be sensibly
        // called without a full arg list, and an outer join must produce
        // no matches for this input.
        final Node[] args = new Node[env.argNodes.size()];
        for (int i = 0; i < args.length; i++) {
            final Node resolved = resolveNode(env.argNodes.get(i), binding);
            if (resolved == null) return QueryIterNullIterator.create(ctx);
            args[i] = resolved;
        }

        // Bind callback context BEFORE invoking so guests such as
        // wf_tree_rows can recurse via execute-query / run-prepared. Same
        // reuse-outer-context contract as WfCall.
        final CallbackContext cbCtx = CallbackContext.bind(functionEnv(ctx));
        final List<WitValueMarshaller.Row> rows;
        // Known v0.1 limit: wasmtime4j 46.0.1-1.2.0's WitValue deserializer
        // infers each variant instance's type from the observed case only,
        // so a row containing a binding with value=iri alongside another
        // with value=literal fails uniform-list validation (records with
        // different variant-value fields are not .equals()). Uniform-
        // variant guests (single-case value across a row) work today. The
        // wf_tree_rows guest is the reference mixed-variant case and is
        // pending an upstream fix. The alternative ComponentVal /
        // runConcurrent codec would sidestep the deserializer but re-
        // instantiates the component into a fresh linker without our
        // host imports, which breaks any wasm using host::execute-query.
        try (JenaWasmInstance instance = new JenaWasmInstance(wasmUrl)) {
            rows = instance.evaluate(args);
        } catch (IOException e) {
            throw new QueryException("wf:call SERVICE failed: " + e.getMessage(), e);
        } finally {
            CallbackContext.unbindIfOutermost(cbCtx);
        }

        final List<Binding> out = new ArrayList<>(rows.size());
        for (WitValueMarshaller.Row row : rows) {
            final BindingBuilder bb = BindingBuilder.create(binding);
            for (Map.Entry<String, Var> e : env.outputVars.entrySet()) {
                final int idx = row.vars.indexOf(e.getKey());
                if (idx < 0) continue;
                final Node value = row.values.get(idx);
                if (value == null) continue; // unbound column stays unbound in the solution
                bb.add(e.getValue(), value);
            }
            out.add(bb.build());
        }
        return QueryIterPlainWrapper.create(out.iterator(), ctx);
    }

    /**
     * Extract wasm URL, ordered args, and output-column mappings from the
     * SERVICE body's BGP. Anything that isn't an OpBGP is a hard error —
     * the wf:call service form is defined by its triple shape.
     */
    private static Envelope parseEnvelope(final Op subOp) {
        if (!(subOp instanceof OpBGP)) {
            throw new QueryException(
                    "wf:call SERVICE body must be a BGP, got: "
                            + (subOp == null ? "null" : subOp.getName()));
        }
        final BasicPattern pattern = ((OpBGP) subOp).getPattern();
        Node wasmNode = null;
        final List<Node> argNodes = new ArrayList<>();
        // LinkedHashMap preserves declaration order — useful for debugging
        // when two output-column patterns collide on the same colname.
        final Map<String, Var> outputVars = new LinkedHashMap<>();
        for (Triple t : pattern) {
            final Node p = t.getPredicate();
            if (!p.isURI()) continue;
            final String pUri = p.getURI();
            if (WF_WASM.equals(pUri)) {
                if (wasmNode != null && !wasmNode.equals(t.getObject())) {
                    throw new QueryException(
                            "wf:call SERVICE: multiple wf:wasm targets — only one is supported");
                }
                wasmNode = t.getObject();
            } else if (WF_ARG.equals(pUri)) {
                argNodes.add(t.getObject());
            } else if (pUri.startsWith(WF_NS)) {
                final String colname = pUri.substring(WF_NS.length());
                final Node obj = t.getObject();
                if (!obj.isVariable()) {
                    throw new QueryException(
                            "wf:call SERVICE: wf:" + colname + " object must be a variable");
                }
                outputVars.put(colname, Var.alloc(obj.getName()));
            }
            // Unknown predicates outside wf: — silently ignore. Lets users
            // decorate the envelope BGP with anchor triples (e.g. a rdf:type
            // hint for readability) without breaking the plugin.
        }
        if (wasmNode == null) {
            throw new QueryException("wf:call SERVICE: no wf:wasm triple found");
        }
        return new Envelope(wasmNode, argNodes, outputVars);
    }

    /** Resolve a node against an outer binding; null iff a variable is unbound. */
    private static Node resolveNode(final Node n, final Binding binding) {
        if (!n.isVariable()) return n;
        final Node v = binding.get(Var.alloc(n.getName()));
        return v; // possibly null — caller treats null as "skip this input"
    }

    private static String nodeAsUrlString(final Node n) {
        if (n.isURI()) return n.getURI();
        if (n.isLiteral()) return n.getLiteralLexicalForm();
        throw new QueryException("wf:call SERVICE: wf:wasm must be an IRI or string literal");
    }

    /**
     * CallbackContext.bind takes a FunctionEnv; ExecutionContext implements it
     * in Jena 6.1 but the interface's exact method set has drifted over
     * releases, so bind through the narrower type by casting here.
     */
    private static org.apache.jena.sparql.function.FunctionEnv functionEnv(
            final ExecutionContext ctx) {
        return ctx;
    }

    /** Parsed SERVICE-body triples in their execution-ready shape. */
    private static final class Envelope {
        final Node wasmNode;
        final List<Node> argNodes;
        final Map<String, Var> outputVars;

        Envelope(final Node wasmNode, final List<Node> argNodes,
                 final Map<String, Var> outputVars) {
            this.wasmNode = wasmNode;
            this.argNodes = Collections.unmodifiableList(argNodes);
            this.outputVars = Collections.unmodifiableMap(outputVars);
        }
    }
}
