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

    // v0.5 sink handles. Vec-backed so the handle IS the index; nulling a
    // slot survives sink-close without reshuffling.  Handles are valid only
    // for the lifetime of the outer wf:call frame that opened them — the
    // frame closes any leftover sinks when it unbinds via
    // {@link #unbindIfOutermost}.
    private final java.util.List<Sink> sinks = new java.util.ArrayList<>();

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
     *
     * <p>Closes any leftover v0.5 sinks the guest didn't explicitly close.
     * The WIT contract says sink handles are only valid within the frame
     * that opened them — this is where the frame ends.
     */
    public static void unbindIfOutermost(final CallbackContext ctx) {
        if (ctx.depth == 0 && CURRENT.get() == ctx) {
            for (Sink s : ctx.sinks) {
                if (s == null) continue;
                try {
                    s.close();
                } catch (Exception ignored) {
                    // Best-effort close on frame exit.
                }
            }
            ctx.sinks.clear();
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

    /**
     * v0.6 execute-query-with-bindings: execute a SPARQL SELECT with a full
     * pre-seed binding matrix (vars + rows) rather than a single row of
     * scalar bindings. Semantics mirror Oxigraph's
     * {@code run_query_with_seed} — the seed is spliced under the outermost
     * projection as a VALUES join, so it composes with any WHERE-only
     * variables the outer SELECT does not project.
     *
     * <p>Missing cells in a row become Jena UNDEF (null in the Binding),
     * matching SPARQL 1.1 VALUES semantics. The seed variables are combined
     * with the query's projected variables in the result-set vars list.
     */
    public ResultSet executeSelectWithBindings(
            final String sparql,
            final java.util.List<org.apache.jena.sparql.core.Var> seedVars,
            final java.util.List<org.apache.jena.sparql.engine.binding.Binding> seedRows) {
        final Query q = QueryFactory.create(sparql);
        if (seedVars.isEmpty() || seedRows.isEmpty()) {
            // No seed → identical semantics to executeSelect with no bindings.
            try (QueryExecution qe = QueryExecutionDatasetBuilder.create()
                    .query(q)
                    .dataset(org.apache.jena.query.DatasetFactory.wrap(dataset))
                    .build()) {
                return ResultSetFactory.copyResults(qe.execSelect());
            }
        }
        final org.apache.jena.sparql.algebra.Op op = org.apache.jena.sparql.algebra.Algebra.compile(q);
        final org.apache.jena.sparql.algebra.table.TableData table =
                new org.apache.jena.sparql.algebra.table.TableData(seedVars, seedRows);
        final org.apache.jena.sparql.algebra.op.OpTable valuesOp =
                org.apache.jena.sparql.algebra.op.OpTable.create(table);
        final org.apache.jena.sparql.algebra.Op transformed = spliceValuesUnderProjection(op, valuesOp);

        // Vars are the union of the query's project vars plus any seed vars
        // (in that order, dedup'd) so seed-only columns still show up in the
        // returned binding-sets — otherwise a seed-only var would disappear
        // even though the join grabbed it from the VALUES row.
        final java.util.List<String> resultVars = new java.util.ArrayList<>(q.getResultVars());
        for (org.apache.jena.sparql.core.Var v : seedVars) {
            if (!resultVars.contains(v.getVarName())) {
                resultVars.add(v.getVarName());
            }
        }
        final org.apache.jena.sparql.engine.QueryIterator qIter =
                org.apache.jena.sparql.algebra.Algebra.exec(transformed, dataset);
        try {
            // Wrap the algebra iterator in a ResultSet keyed by the union var
            // list, then copy into memory so the caller can iterate freely
            // after we close the underlying algebra iterator.
            final ResultSet rss = org.apache.jena.sparql.engine.ResultSetStream.create(
                    resultVars,
                    org.apache.jena.rdf.model.ModelFactory.createDefaultModel(),
                    qIter);
            return ResultSetFactory.copyResults(rss);
        } finally {
            qIter.close();
        }
    }

    /**
     * Walk down through Project/Distinct/Reduced/OrderBy/Slice/Group/Filter/
     * Extend wrappers until we reach the scan/join pattern, then wrap it in
     * {@code Join(values, inner)}. Same splice path Oxigraph uses so seed
     * columns compose with WHERE-only variables, not just the outer SELECT's
     * projection.
     */
    private static org.apache.jena.sparql.algebra.Op spliceValuesUnderProjection(
            final org.apache.jena.sparql.algebra.Op op,
            final org.apache.jena.sparql.algebra.Op values) {
        if (op instanceof org.apache.jena.sparql.algebra.op.OpProject p) {
            return new org.apache.jena.sparql.algebra.op.OpProject(
                    spliceValuesUnderProjection(p.getSubOp(), values), p.getVars());
        }
        if (op instanceof org.apache.jena.sparql.algebra.op.OpDistinct d) {
            return new org.apache.jena.sparql.algebra.op.OpDistinct(
                    spliceValuesUnderProjection(d.getSubOp(), values));
        }
        if (op instanceof org.apache.jena.sparql.algebra.op.OpReduced r) {
            return org.apache.jena.sparql.algebra.op.OpReduced.create(
                    spliceValuesUnderProjection(r.getSubOp(), values));
        }
        if (op instanceof org.apache.jena.sparql.algebra.op.OpOrder o) {
            return new org.apache.jena.sparql.algebra.op.OpOrder(
                    spliceValuesUnderProjection(o.getSubOp(), values), o.getConditions());
        }
        if (op instanceof org.apache.jena.sparql.algebra.op.OpSlice s) {
            return new org.apache.jena.sparql.algebra.op.OpSlice(
                    spliceValuesUnderProjection(s.getSubOp(), values),
                    s.getStart(), s.getLength());
        }
        if (op instanceof org.apache.jena.sparql.algebra.op.OpGroup g) {
            return new org.apache.jena.sparql.algebra.op.OpGroup(
                    spliceValuesUnderProjection(g.getSubOp(), values),
                    g.getGroupVars(), g.getAggregators());
        }
        if (op instanceof org.apache.jena.sparql.algebra.op.OpFilter f) {
            return org.apache.jena.sparql.algebra.op.OpFilter.filterBy(
                    f.getExprs(),
                    spliceValuesUnderProjection(f.getSubOp(), values));
        }
        if (op instanceof org.apache.jena.sparql.algebra.op.OpExtend e) {
            return org.apache.jena.sparql.algebra.op.OpExtend.create(
                    spliceValuesUnderProjection(e.getSubOp(), values),
                    e.getVarExprList());
        }
        return org.apache.jena.sparql.algebra.op.OpJoin.create(values, op);
    }

    // ---- v0.5 sink handle table -------------------------------------------

    /** v0.5 sink-open: install {@code s} in the slot table, return its index. */
    public int addSink(final Sink s) {
        sinks.add(s);
        return sinks.size() - 1;
    }

    /** v0.5 sink-execute: look up an open sink by handle, or null if closed/stale. */
    public Sink getSink(final int handle) {
        if (handle < 0 || handle >= sinks.size()) return null;
        return sinks.get(handle);
    }

    /**
     * v0.5 sink-close: null out the slot so future sink-execute against the
     * same handle returns a stale-handle error. Returns true iff the slot
     * was populated.
     */
    public boolean closeSink(final int handle) throws Exception {
        if (handle < 0 || handle >= sinks.size()) return false;
        final Sink s = sinks.get(handle);
        if (s == null) return false;
        sinks.set(handle, null);
        s.close();
        return true;
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
