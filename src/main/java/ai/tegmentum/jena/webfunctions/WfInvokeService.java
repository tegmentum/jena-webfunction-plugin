package ai.tegmentum.jena.webfunctions;

import ai.tegmentum.jena.webfunctions.rewrite.InvokeRegistry;
import ai.tegmentum.jena.webfunctions.rewrite.RewritePipeline;
import ai.tegmentum.jena.webfunctions.rewrite.WebFunctionQueryEngine;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.QueryException;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVisitorBase;
import org.apache.jena.sparql.algebra.OpWalker;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpService;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingBuilder;
import org.apache.jena.sparql.engine.iterator.QueryIterPlainWrapper;
import org.apache.jena.sparql.service.single.ChainingServiceExecutor;
import org.apache.jena.sparql.service.single.ServiceExecutor;
import org.apache.jena.sparql.util.Context;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles {@code SERVICE <wf-invoke:<hex-id>>} — the folded form emitted by
 * {@link ai.tegmentum.jena.webfunctions.rewrite.PartialRewrite}.
 *
 * <p>{@code wf:partial(<wasm>, const-args…)} is constant-folded at rewrite
 * time into an opaque {@code wf-invoke:} IRI whose id keys an
 * {@link InvokeRegistry.InvokeSpec} on the plugin's {@link InvokeRegistry}.
 * At evaluation time this executor looks the id back up, marshals the
 * stashed args, invokes the referenced wasm through
 * {@link JenaWasmInstance}, and yields the guest's {@code binding-sets}
 * return as SERVICE result bindings.
 *
 * <p>Mirrors {@code oxigraph-wf/src/partial.rs::WfPartialDispatchHandler}.
 * Column projection uses the same {@code _:o wf:<column> ?var} shape as the
 * {@link WfCallServiceExecutor} envelope, with a fall-back to the guest's
 * own variable names when the SERVICE body carries no projection triples.
 *
 * <p>Registry lookup uses {@link InvokeRegistry#get(long)} rather than
 * {@link InvokeRegistry#take(long)}: Jena calls the SERVICE executor once
 * per outer binding, so a destructive read would fail on the second and
 * subsequent bindings. The entry stays live for the life of the enclosing
 * query.
 */
public final class WfInvokeService implements ChainingServiceExecutor {

    private static final String WF_NS = "http://tegmentum.ai/ns/webfunction/";

    @Override
    public QueryIterator createExecution(
            final OpService opService,
            final OpService original,
            final Binding binding,
            final ExecutionContext ctx,
            final ServiceExecutor next) {
        final Node svcIri = opService.getService();
        if (!svcIri.isURI() || !svcIri.getURI().startsWith(InvokeRegistry.WF_INVOKE_SCHEME)) {
            return next.createExecution(opService, original, binding, ctx);
        }
        final String iri = svcIri.getURI();
        final long id = InvokeRegistry.idFromIri(iri).orElseThrow(() ->
                new QueryException("wf-invoke: IRI did not parse to a registered id: " + iri));

        final InvokeRegistry registry = registryFrom(ctx);
        if (registry == null) {
            throw new QueryException(
                    "wf-invoke: no InvokeRegistry on the query context — "
                    + "wf:partial fold and dispatch require a RewritePipeline.Context "
                    + "installed via WebFunctionQueryEngine.installGlobal(...)");
        }
        final InvokeRegistry.InvokeSpec spec = registry.get(id).orElseThrow(() ->
                new QueryException(
                        "wf-invoke: id " + iri + " was never allocated (or already reaped)"));

        final URL wasmUrl;
        try {
            wasmUrl = new URL(spec.wasmUrl);
        } catch (MalformedURLException e) {
            throw new QueryException("wf-invoke: bad wasm URL: " + spec.wasmUrl, e);
        }

        // Args were captured as constants at partial-fold time — no
        // per-binding evaluation needed here.
        final Node[] args = spec.args.toArray(new Node[0]);

        // Discover output-column bindings in the SERVICE body (the same
        // `_:o wf:<column> ?var` shape as WfCallServiceExecutor). If the
        // body has none, fall back to projecting on the guest's own
        // variable names so callers get a useful default.
        final Map<String, Var> outputVars = collectOutputBindings(opService.getSubOp());

        final CallbackContext cbCtx = CallbackContext.bind(ctx);
        final List<WitValueMarshaller.Row> rows;
        try (JenaWasmInstance instance = new JenaWasmInstance(wasmUrl)) {
            rows = instance.evaluate(args);
        } catch (IOException e) {
            throw new QueryException("wf-invoke: " + e.getMessage(), e);
        } finally {
            CallbackContext.unbindIfOutermost(cbCtx);
        }

        final List<Binding> out = new ArrayList<>(rows.size());
        for (WitValueMarshaller.Row row : rows) {
            final BindingBuilder bb = BindingBuilder.create(binding);
            if (outputVars.isEmpty()) {
                // Fall back to guest column names as caller variables —
                // matches the Rust WfPartialDispatchHandler's default.
                for (int i = 0; i < row.vars.size(); i++) {
                    final Node v = row.values.get(i);
                    if (v == null) continue;
                    bb.add(Var.alloc(row.vars.get(i)), v);
                }
            } else {
                for (Map.Entry<String, Var> e : outputVars.entrySet()) {
                    final int idx = row.vars.indexOf(e.getKey());
                    if (idx < 0) continue;
                    final Node v = row.values.get(idx);
                    if (v == null) continue;
                    bb.add(e.getValue(), v);
                }
            }
            out.add(bb.build());
        }
        return QueryIterPlainWrapper.create(out.iterator(), ctx);
    }

    /**
     * Walk the SERVICE body looking for {@code ?onode wf:<column> ?var}
     * triples. Insertion-ordered to preserve declaration order for
     * error-reporting on collisions (later triple wins, but insertion
     * order tells the story).
     */
    private static Map<String, Var> collectOutputBindings(final Op subOp) {
        final Map<String, Var> out = new LinkedHashMap<>();
        if (subOp == null) return out;
        OpWalker.walk(subOp, new OpVisitorBase() {
            @Override
            public void visit(final OpBGP opBGP) {
                for (Triple t : opBGP.getPattern()) {
                    final Node p = t.getPredicate();
                    if (!p.isURI()) continue;
                    final String pUri = p.getURI();
                    if (!pUri.startsWith(WF_NS)) continue;
                    final String col = pUri.substring(WF_NS.length());
                    // Skip the config-side predicates the wf:call envelope
                    // uses — those describe the wasm URL, not output vars.
                    if ("wasm".equals(col) || "arg".equals(col) || "call".equals(col)) {
                        continue;
                    }
                    final Node obj = t.getObject();
                    if (!obj.isVariable()) continue;
                    out.put(col, Var.alloc(obj.getName()));
                }
            }
        });
        return out;
    }

    private static InvokeRegistry registryFrom(final ExecutionContext ctx) {
        final Context arqCtx = ctx.getContext();
        if (arqCtx == null) return null;
        final Object pipelineObj = arqCtx.get(WebFunctionQueryEngine.PIPELINE_SYMBOL);
        if (!(pipelineObj instanceof RewritePipeline.Context pipelineCtx)) return null;
        return pipelineCtx.invokeRegistry;
    }
}
