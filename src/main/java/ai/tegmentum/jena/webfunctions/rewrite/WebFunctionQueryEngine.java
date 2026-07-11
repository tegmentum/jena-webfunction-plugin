package ai.tegmentum.jena.webfunctions.rewrite;

import org.apache.jena.query.Query;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.engine.Plan;
import org.apache.jena.sparql.engine.QueryEngineFactory;
import org.apache.jena.sparql.engine.QueryEngineRegistry;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.main.QueryEngineMain;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.sparql.util.Symbol;

/**
 * Query engine that runs the webfunction rewrite pipeline before ARQ's
 * standard evaluation.
 *
 * <p>Registered ahead of {@link QueryEngineMain} in
 * {@link QueryEngineRegistry} so the {@code accept}/{@code create}
 * chain finds this factory first for every non-quad query. The engine
 * itself is a {@link QueryEngineMain} subclass that overrides
 * {@link QueryEngineMain#modifyOp(Op)} to invoke
 * {@link RewritePipeline}; the resulting
 * {@link AliasRewriteState} is stashed on the execution
 * {@link Context} under {@link #ALIAS_STATE_SYMBOL} so the result
 * serializer (or the caller iterating solutions) can retrieve it and
 * rewrite output IRIs back to the alias the caller mentioned.
 */
public final class WebFunctionQueryEngine extends QueryEngineMain {

    /**
     * ARQ context key under which the per-query
     * {@link AliasRewriteState} is stored after the pipeline runs.
     * Callers reading the SELECT result set should look this up and
     * pass every {@link org.apache.jena.query.QuerySolution} through
     * {@link AliasRewriteState#rewriteSolution(org.apache.jena.query.QuerySolution)}.
     */
    public static final Symbol ALIAS_STATE_SYMBOL =
            Symbol.create("ai.tegmentum.jena.webfunctions.aliasState");

    /**
     * ARQ context key under which the {@link RewritePipeline.Context}
     * lives. The plugin loader populates this once at startup.
     */
    public static final Symbol PIPELINE_SYMBOL =
            Symbol.create("ai.tegmentum.jena.webfunctions.rewritePipelineContext");

    private WebFunctionQueryEngine(final Query query,
                                   final DatasetGraph dataset,
                                   final Binding input,
                                   final Context ctx) {
        super(query, dataset, input, ctx);
    }

    private WebFunctionQueryEngine(final Op op,
                                   final DatasetGraph dataset,
                                   final Binding input,
                                   final Context ctx) {
        super(op, dataset, input, ctx);
    }

    @Override
    protected Op modifyOp(final Op op) {
        final Op superModified = super.modifyOp(op);
        final RewritePipeline.Context pipelineCtx = pipelineContext(super.context);
        if (pipelineCtx == null) {
            return superModified;
        }
        final RewritePipeline.Result res = RewritePipeline.run(superModified, pipelineCtx);
        super.context.set(ALIAS_STATE_SYMBOL, res.aliasState);
        return res.rewrittenOp;
    }

    private static RewritePipeline.Context pipelineContext(final Context ctx) {
        if (ctx == null) return null;
        final Object v = ctx.get(PIPELINE_SYMBOL);
        return v instanceof RewritePipeline.Context c ? c : null;
    }

    // ---------------------------------------------------------------
    // Factory + registration
    // ---------------------------------------------------------------

    /** Factory instance registered in ARQ's QueryEngineRegistry. */
    public static final QueryEngineFactory FACTORY = new Factory();

    /**
     * Add this factory to ARQ's {@link QueryEngineRegistry}. Idempotent —
     * subsequent calls no-op. Safe to call from Jena's subsystem
     * lifecycle {@code start()}.
     */
    public static synchronized void register() {
        for (QueryEngineFactory f : QueryEngineRegistry.get().factories()) {
            if (f == FACTORY) return;
        }
        QueryEngineRegistry.get().add(FACTORY);
    }

    public static synchronized void unregister() {
        QueryEngineRegistry.get().remove(FACTORY);
    }

    /**
     * Install a rewrite pipeline context globally. Every query
     * evaluated afterwards sees these registries. Callers wanting
     * per-request contexts can set the same {@link Symbol} on a
     * per-query ARQ Context instead.
     */
    public static void installGlobal(final RewritePipeline.Context ctx) {
        org.apache.jena.query.ARQ.getContext().set(PIPELINE_SYMBOL, ctx);
    }

    private static final class Factory implements QueryEngineFactory {
        @Override
        public boolean accept(final Query query,
                              final DatasetGraph dataset,
                              final Context context) {
            // Only intercept when a pipeline is configured. Otherwise
            // the pass would be identity anyway and we shouldn't
            // shadow QueryEngineMain gratuitously.
            return context != null && context.isDefined(PIPELINE_SYMBOL);
        }

        @Override
        public Plan create(final Query query,
                           final DatasetGraph dataset,
                           final Binding inputBinding,
                           final Context context) {
            final WebFunctionQueryEngine engine =
                    new WebFunctionQueryEngine(query, dataset, inputBinding, context);
            return engine.getPlan();
        }

        @Override
        public boolean accept(final Op op,
                              final DatasetGraph dataset,
                              final Context context) {
            return context != null && context.isDefined(PIPELINE_SYMBOL);
        }

        @Override
        public Plan create(final Op op,
                           final DatasetGraph dataset,
                           final Binding inputBinding,
                           final Context context) {
            final WebFunctionQueryEngine engine =
                    new WebFunctionQueryEngine(op, dataset, inputBinding, context);
            return engine.getPlan();
        }
    }
}
