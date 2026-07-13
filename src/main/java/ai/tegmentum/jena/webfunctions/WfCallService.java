package ai.tegmentum.jena.webfunctions;

import ai.tegmentum.jena.webfunctions.rewrite.InvokeRegistry;
import ai.tegmentum.jena.webfunctions.rewrite.ShapeRewrite;

import org.apache.jena.graph.Node;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.op.OpExtend;
import org.apache.jena.sparql.algebra.op.OpService;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingBuilder;
import org.apache.jena.sparql.engine.iterator.QueryIterPlainWrapper;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionEnv;
import org.apache.jena.sparql.service.single.ChainingServiceExecutor;
import org.apache.jena.sparql.service.single.ServiceExecutor;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;

/**
 * ChainingServiceExecutor that handles {@code SERVICE <wasm-url> { BIND(...) }}.
 *
 * <p>The SERVICE URI is treated as the wasm component URL. Inner {@code BIND}
 * expressions bound to variables named {@code ?arg0}, {@code ?arg1}, … supply
 * positional arguments to the component's {@code evaluate} export. The rows in
 * the component's {@code binding-sets} return are yielded as SERVICE result
 * bindings, joined with the parent binding.
 *
 * <p>Matches SERVICE URIs whose scheme is {@code file}, {@code http}, {@code https},
 * or {@code ipfs}. Everything else is delegated to the next executor in the
 * chain (Jena's default HTTP SPARQL executor).
 */
public final class WfCallService implements ChainingServiceExecutor {

    @Override
    public QueryIterator createExecution(
            final OpService opService,
            final OpService original,
            final Binding binding,
            final ExecutionContext ctx,
            final ServiceExecutor next) {
        final Node serviceNode = opService.getService();
        if (!serviceNode.isURI() || !matchesWasmUrl(serviceNode.getURI())) {
            return next.createExecution(opService, original, binding, ctx);
        }

        final URL wasmUrl;
        try {
            wasmUrl = new URL(serviceNode.getURI());
        } catch (MalformedURLException e) {
            return next.createExecution(opService, original, binding, ctx);
        }

        final Node[] args = extractArgs(opService.getSubOp(), binding, (FunctionEnv) ctx);

        final List<WitValueMarshaller.Row> rows;
        try (JenaWasmInstance instance = new JenaWasmInstance(wasmUrl)) {
            rows = instance.evaluate(args);
        } catch (IOException e) {
            throw new org.apache.jena.query.QueryException("wf:call SERVICE failed: " + e.getMessage(), e);
        }

        final List<Binding> outputBindings = new ArrayList<>(rows.size());
        for (WitValueMarshaller.Row row : rows) {
            final BindingBuilder bb = BindingBuilder.create(binding);
            for (int i = 0; i < row.vars.size(); i++) {
                final Node v = row.values.get(i);
                if (v != null) bb.add(Var.alloc(row.vars.get(i)), v);
            }
            outputBindings.add(bb.build());
        }
        final Iterator<Binding> it = outputBindings.iterator();
        return QueryIterPlainWrapper.create(it, ctx);
    }

    private static boolean matchesWasmUrl(final String uri) {
        // Exclude the substrate's own dispatch IRIs — they use http: or a
        // dedicated scheme but must never be handed to the wasm loader.
        // Without this guard the SERVICE handler would HTTP-fetch its own
        // dispatch URL, get empty bytes, and crash wasmtime4j with
        // "Parameter 'componentBytes' must not be empty".
        if (ShapeRewrite.WF_CALL_IRI.equals(uri)) {
            return false;
        }
        final String lower = uri.toLowerCase();
        if (lower.startsWith(InvokeRegistry.WF_INVOKE_SCHEME)) {
            return false;
        }
        // For http(s) URLs, require an explicit `.wasm` path suffix. Without
        // this guard, the wf_federation rewrite pass — which emits
        // `SERVICE <http://.../query>` clauses whose URLs point at plain
        // SPARQL endpoints — would collide with this handler: WfCallService
        // would HTTP-GET the SPARQL endpoint, receive SPARQL Results JSON,
        // and hand that to wasmtime4j, which of course fails to compile it
        // as a WASM component. See wf-conformance `federation_sparql_only`
        // for the regression scenario. `file:` and `ipfs:` never carry
        // SPARQL federation traffic, so their existing prefix match stays.
        if (lower.startsWith("http:") || lower.startsWith("https:")) {
            return hasWasmSuffix(lower);
        }
        return lower.startsWith("file:") || lower.startsWith("ipfs:");
    }

    /**
     * Return true when {@code lower} (already lowercased) ends with
     * {@code .wasm}, optionally followed by a URL query string or fragment.
     * Package-private for the tests.
     */
    static boolean hasWasmSuffix(final String lower) {
        int end = lower.length();
        final int q = lower.indexOf('?');
        if (q >= 0) end = Math.min(end, q);
        final int f = lower.indexOf('#');
        if (f >= 0) end = Math.min(end, f);
        return lower.regionMatches(true, end - 5, ".wasm", 0, 5);
    }

    /**
     * Walk the SERVICE sub-op looking for OpExtend nodes that bind
     * {@code ?arg0}, {@code ?arg1}, …; evaluate each against the parent
     * binding to produce a positional args array.
     */
    private static Node[] extractArgs(final Op op, final Binding binding, final FunctionEnv env) {
        final TreeMap<Integer, Expr> byIndex = new TreeMap<>();
        Op cursor = op;
        while (cursor instanceof OpExtend ext) {
            for (Var v : ext.getVarExprList().getVars()) {
                final String name = v.getVarName();
                if (!name.startsWith("arg")) continue;
                try {
                    final int idx = Integer.parseInt(name.substring(3));
                    byIndex.put(idx, ext.getVarExprList().getExpr(v));
                } catch (NumberFormatException ignore) {}
            }
            cursor = ext.getSubOp();
        }
        if (byIndex.isEmpty()) return new Node[0];
        final Node[] args = new Node[byIndex.lastKey() + 1];
        for (java.util.Map.Entry<Integer, Expr> e : byIndex.entrySet()) {
            final NodeValue nv = e.getValue().eval(binding, env);
            args[e.getKey()] = nv.asNode();
        }
        return args;
    }
}
