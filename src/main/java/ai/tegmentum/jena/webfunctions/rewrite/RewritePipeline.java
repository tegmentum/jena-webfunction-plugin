package ai.tegmentum.jena.webfunctions.rewrite;

import org.apache.jena.sparql.algebra.Op;

/**
 * Orchestrates the four webfunction query-rewrite passes.
 *
 * <p>Runs the passes in the same order as
 * {@code oxigraph-wf/src/main.rs} (lines 630–665 of that file):
 *
 * <ol>
 *   <li>{@link PartialRewrite} — constant-fold {@code wf:partial(...)}
 *       so downstream passes see the folded {@code wf-invoke:} IRIs
 *       instead of the FunctionCall expression.</li>
 *   <li>{@link ConversionRewrite} — expand virtual
 *       {@code urn:wf:conversion:*} named graphs into computed
 *       triples.</li>
 *   <li>{@link AliasRewrite} — substitute IRI aliases with their
 *       canonical form and record the reverse map for the solution
 *       serializer.</li>
 *   <li>{@link ShapeRewrite} — replace shape-covered BGPs with
 *       {@code SERVICE <wf:call>} against {@code wf_fetch.wasm}.</li>
 * </ol>
 *
 * <p>Any registry that is empty (or a null fetch URL for
 * ShapeRewrite) short-circuits its pass to identity — a plugin
 * configured with no shapes/conversions/aliases pays effectively zero
 * cost.
 */
public final class RewritePipeline {

    /** Bag of side-inputs and side-outputs for a single rewrite. */
    public static final class Context {
        public final InvokeRegistry invokeRegistry;
        public final ConversionRegistry conversionRegistry;
        public final AliasMap aliasMap;
        public final ShapeRegistry shapeRegistry;
        public final String wfFetchUrl;

        public Context(final InvokeRegistry invokeRegistry,
                       final ConversionRegistry conversionRegistry,
                       final AliasMap aliasMap,
                       final ShapeRegistry shapeRegistry,
                       final String wfFetchUrl) {
            this.invokeRegistry = invokeRegistry;
            this.conversionRegistry = conversionRegistry;
            this.aliasMap = aliasMap;
            this.shapeRegistry = shapeRegistry;
            this.wfFetchUrl = wfFetchUrl;
        }
    }

    public static final class Result {
        public final Op rewrittenOp;
        public final AliasRewriteState aliasState;

        Result(final Op rewrittenOp, final AliasRewriteState aliasState) {
            this.rewrittenOp = rewrittenOp;
            this.aliasState = aliasState;
        }
    }

    private RewritePipeline() {}

    public static Result run(final Op op, final Context ctx) {
        Op cursor = op;
        // 1. wf:partial fold — constant expressions become opaque IRIs.
        cursor = PartialRewrite.rewrite(cursor, ctx.invokeRegistry);
        // 2. Conversion rewrite — virtual conversion graphs become BGPs.
        cursor = ConversionRewrite.rewrite(cursor, ctx.conversionRegistry);
        // 3. Alias rewrite — alias → canonical everywhere.
        final AliasRewrite.Result aliasRes = AliasRewrite.rewrite(cursor, ctx.aliasMap);
        cursor = aliasRes.rewrittenOp;
        // 4. Shape rewrite — cover BGPs with SERVICE <wf:call>.
        cursor = ShapeRewrite.rewrite(cursor, ctx.shapeRegistry, ctx.wfFetchUrl);
        return new Result(cursor, aliasRes.state);
    }
}
