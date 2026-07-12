package ai.tegmentum.jena.webfunctions;

import ai.tegmentum.jena.webfunctions.rewrite.WebFunctionQueryEngine;
import org.apache.jena.sparql.expr.aggregate.AggregateRegistry;
import org.apache.jena.sparql.function.FunctionRegistry;
import org.apache.jena.sparql.pfunction.PropertyFunctionRegistry;
import org.apache.jena.sparql.service.ServiceExecutorRegistry;
import org.apache.jena.sys.JenaSubsystemLifecycle;

/**
 * Auto-registers the webfunction ARQ functions when Jena's subsystem loader
 * discovers this class via {@code META-INF/services/org.apache.jena.sys.JenaSubsystemLifecycle}.
 *
 * <p>Callers using a non-standard initialization path can call {@link #register()}
 * directly.
 */
public final class WebFunctionInit implements JenaSubsystemLifecycle {

    @Override
    public void start() {
        register();
    }

    @Override
    public void stop() {
        // FunctionRegistry entries are process-lifetime; nothing to unwind.
    }

    @Override
    public int level() {
        // Jena's own SPARQL/ARQ subsystems live in the 100-500 range; register
        // after them so FunctionRegistry.get() is guaranteed non-null.
        return 700;
    }

    /**
     * Register all webfunction ARQ symbols. Idempotent; safe to call more than once.
     */
    public static void register() {
        FunctionRegistry.get().put(WfCall.URI, WfCall.class);
        PropertyFunctionRegistry.get().put(WfCallPropertyFunction.URI, WfCallPropertyFunction.class);
        AggregateRegistry.register(WfCallAgg.URI, new WfCallAgg());
        // Order matters, but not the way it reads — Jena's
        // ServiceExecutorRegistry.addSingleLink PREPENDS to the chain, so
        // the LAST executor registered here wins the dispatch. The URL
        // guard in each handler (matches its scheme/IRI, else calls the
        // next in the chain) is the real correctness invariant.
        //
        // We register:
        //   1. WfCallServiceExecutor — matches SERVICE <wf:call> or
        //      SERVICE <http://tegmentum.ai/ns/webfunction/call> (BGP-envelope
        //      form, populated by ShapeRewrite).
        //   2. WfCallService — matches SERVICE URIs that ARE the wasm URL
        //      (file:/http:/https:/ipfs:), excluding the two dispatch IRIs
        //      above.
        //   3. WfInvokeService — matches SERVICE <wf-invoke:<hex>>, the
        //      folded form emitted by PartialRewrite.
        ServiceExecutorRegistry.get().addSingleLink(new WfCallServiceExecutor());
        ServiceExecutorRegistry.get().addSingleLink(new WfCallService());
        ServiceExecutorRegistry.get().addSingleLink(new WfInvokeService());
        // Install the query-rewrite pipeline factory. The engine is a
        // no-op until callers register a RewritePipeline.Context; see
        // WebFunctionQueryEngine.installGlobal for the wiring shape.
        WebFunctionQueryEngine.register();
    }
}
