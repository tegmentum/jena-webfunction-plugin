package ai.tegmentum.jena.webfunctions;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionDatasetBuilder;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolutionMap;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFactory;
import org.apache.jena.graph.Node;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.function.FunctionEnv;

/**
 * Per-thread context for host callback imports in the v0.3.0 WIT world.
 *
 * <p>Attaches to the current thread when {@link WfCall#exec} is invoked, so any
 * nested wasm callback (via the {@code execute-query} import) sees the same
 * {@link DatasetGraph} the outer query is running against.
 *
 * <p>Jena's model differs slightly from RDF4J: rather than binding a strategy,
 * we bind a {@link FunctionEnv} and derive the dataset from it. Sub-queries
 * go through {@link QueryExecutionFactory}, so any registered functions
 * (including nested wf calls) work exactly as they would at the top level.
 *
 * <p>Threading: Jena queries execute single-threaded per QueryExecution, so
 * a {@link ThreadLocal} is sufficient. Nested wf calls share the same
 * context, incrementing the depth counter.
 */
public final class CallbackContext {

    private static final ThreadLocal<CallbackContext> CURRENT = new ThreadLocal<>();

    private final DatasetGraph dataset;
    private final int maxDepth;
    private final int maxRows;
    private int depth = 0;

    // v0.3.2 prepared-query handles. Keyed by opaque u32 the guest carries.
    // Storing the parsed Query lets runPrepared skip Jena's SPARQL parser
    // (~200 µs on the wf_tree recursion query) on every call.
    private final java.util.Map<Integer, Query> prepared = new java.util.HashMap<>();
    private int nextHandle = 1;

    private CallbackContext(final DatasetGraph dataset, final int maxDepth, final int maxRows) {
        this.dataset = dataset;
        this.maxDepth = maxDepth;
        this.maxRows = maxRows;
    }

    /**
     * Attach a context to the current thread. If a context is already
     * bound AND has non-zero depth (we're inside a nested wf callback),
     * reuse it so the depth counter is preserved. Otherwise ALWAYS
     * replace — a lingering context from a previous outer wf:call
     * carries the previous query's DatasetGraph and would silently
     * misroute sub-queries to the wrong data (caught by cross-test
     * leakage of a stale Dataset).
     */
    public static CallbackContext bind(final FunctionEnv env) {
        final CallbackContext existing = CURRENT.get();
        if (existing != null) return existing;
        final CallbackContext ctx = new CallbackContext(
                env.getDataset(),
                WebFunctionConfig.callbackMaxDepth(),
                WebFunctionConfig.callbackMaxRows());
        CURRENT.set(ctx);
        return ctx;
    }

    /**
     * Unbind only when the ctx passed is the outermost binding (its depth is 0
     * and it matches the currently bound one). Nested calls don't unbind.
     */
    public static void unbindIfOutermost(final CallbackContext ctx) {
        if (ctx.depth == 0 && CURRENT.get() == ctx) {
            CURRENT.remove();
        }
    }

    public static CallbackContext current() {
        return CURRENT.get();
    }

    public int enter() {
        if (depth >= maxDepth) {
            throw new RuntimeException(
                "wf callback depth limit exceeded: " + maxDepth
                + " (config: webfunctions.callback.max.depth)");
        }
        return ++depth;
    }

    public int exit() {
        return --depth;
    }

    public int depth() {
        return depth;
    }

    public int maxRows() {
        return maxRows;
    }

    public DatasetGraph dataset() {
        return dataset;
    }

    /**
     * Execute a SPARQL SELECT with the given initial bindings pre-applied and
     * return the materialised {@link ResultSet}. The sub-query goes through
     * the standard Jena executor with the same dataset the outer query saw.
     *
     * <p>Uses {@code substitution(...)} on the Jena 6.x builder — the
     * initial-binding path renamed from earlier releases. Every variable in
     * the QuerySolutionMap gets substituted into the query's algebra before
     * evaluation, matching the SPARQL 1.1 initial-bindings semantics.
     */
    public ResultSet executeSelect(final String sparql, final QuerySolutionMap initial) {
        final Query q = QueryFactory.create(sparql);
        try (QueryExecution qe = QueryExecutionDatasetBuilder.create()
                .query(q)
                .dataset(org.apache.jena.query.DatasetFactory.wrap(dataset))
                .substitution(initial)
                .build()) {
            return ResultSetFactory.copyResults(qe.execSelect());
        }
    }

    /** v0.3.2 prepare-query — parse once, return a handle. */
    public int prepare(final String sparql) {
        final int h = nextHandle++;
        prepared.put(h, QueryFactory.create(sparql));
        return h;
    }

    /**
     * v0.3.3 follow-predicate — direct triple-pattern lookup via
     * {@link DatasetGraph#find}. Returns the objects of all triples
     * matching {@code (subject, predicate, ?)} across the union of all
     * named graphs plus the default graph. Skips SPARQL entirely.
     */
    public java.util.List<Node> followPredicate(final Node subject, final Node predicate) {
        final java.util.List<Node> out = new java.util.ArrayList<>();
        final java.util.Iterator<org.apache.jena.sparql.core.Quad> it =
                dataset.find(org.apache.jena.graph.Node.ANY, subject, predicate,
                             org.apache.jena.graph.Node.ANY);
        while (it.hasNext()) out.add(it.next().getObject());
        return out;
    }

    /** v0.3.2 run-prepared — evaluate a prepared handle with fresh bindings. */
    public ResultSet runPrepared(final int handle, final QuerySolutionMap initial) {
        final Query q = prepared.get(handle);
        if (q == null) {
            throw new RuntimeException("wf callback: unknown prepared handle " + handle);
        }
        try (QueryExecution qe = QueryExecutionDatasetBuilder.create()
                .query(q)
                .dataset(org.apache.jena.query.DatasetFactory.wrap(dataset))
                .substitution(initial)
                .build()) {
            return ResultSetFactory.copyResults(qe.execSelect());
        }
    }

    /**
     * v0.3.1 execute-update — SPARQL 1.1 UPDATE against the same
     * {@link DatasetGraph} the outer query is reading. Jena applies the
     * update directly to the dataset graph, so subsequent
     * {@link #executeSelect} calls in the same wasm frame see the effect.
     *
     * <p>Initial bindings are substituted into the update text at parse time
     * (variables become their bound Values). Jena's
     * {@code UpdateRequest#parseExecute} accepts a QuerySolutionMap for
     * exactly this.
     */
    public void executeUpdate(final String sparql, final QuerySolutionMap initial) {
        // BindingLib.asBinding converts a QuerySolutionMap → sparql-engine Binding
        // for the UpdateExec builder's substitution(...) input.
        final org.apache.jena.sparql.engine.binding.Binding b =
                org.apache.jena.sparql.engine.binding.BindingLib.asBinding(initial);
        org.apache.jena.sparql.exec.UpdateExecDatasetBuilder.create()
                .dataset(dataset)
                .update(sparql)
                .substitution(b)
                .build()
                .execute();
    }
}
