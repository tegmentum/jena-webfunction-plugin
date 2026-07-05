package ai.tegmentum.jena.webfunctions;

import org.apache.jena.sparql.function.FunctionRegistry;
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
    }
}
