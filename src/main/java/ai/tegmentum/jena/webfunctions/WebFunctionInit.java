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
        // Order matters: the WfCallServiceExecutor matches SERVICE URI
        // "wf:call" first so its BGP-envelope form takes precedence over
        // the older SERVICE-URI-is-the-wasm-URL form handled by
        // WfCallService. Both remain live so callers pick the shape that
        // suits them.
        ServiceExecutorRegistry.get().addSingleLink(new WfCallServiceExecutor());
        ServiceExecutorRegistry.get().addSingleLink(new WfCallService());
        // Install the query-rewrite pipeline factory. The engine is a
        // no-op until callers register a RewritePipeline.Context; see
        // WebFunctionQueryEngine.installGlobal for the wiring shape.
        WebFunctionQueryEngine.register();
    }
}
