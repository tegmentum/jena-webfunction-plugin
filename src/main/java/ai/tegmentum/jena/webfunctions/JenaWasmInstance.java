package ai.tegmentum.jena.webfunctions;

import ai.tegmentum.webassembly4j.api.Component;
import ai.tegmentum.webassembly4j.api.ComponentInstance;
import ai.tegmentum.webassembly4j.api.DefaultLinkingContext;
import ai.tegmentum.webassembly4j.api.Engine;
import ai.tegmentum.wasmtime4j.wit.WitResult;
import ai.tegmentum.wasmtime4j.wit.WitString;
import ai.tegmentum.wasmtime4j.wit.WitU64;
import ai.tegmentum.wasmtime4j.wit.WitValue;

import org.apache.jena.graph.Node;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;

/**
 * Component-model WASM instance for the Jena binding. Loads a {@code .wasm}
 * component from a URL and dispatches WIT-typed calls into it.
 *
 * <p>Every top-level constructor builds its own {@link Engine} and {@link
 * Component}. Callers should hold onto the instance while it is in use and
 * {@link #close()} it when done.
 */
public final class JenaWasmInstance implements Closeable {

    private Engine engine;
    private Component component;
    private ComponentInstance instance;
    private boolean closed;

    public JenaWasmInstance(final URL wasmUrl) throws IOException {
        this.engine = WebFunctionConfig.buildEngine();
        if (!engine.capabilities().supportsComponents()) {
            engine.close();
            throw new IllegalStateException("engine '"
                    + engine.info().engineId() + "' does not support components");
        }
        this.component = engine.loadComponent(readAll(wasmUrl));
        this.instance = component.instantiate(DefaultLinkingContext.builder().build());
    }

    /**
     * Invoke the component's {@code evaluate} export with the given arguments.
     * Returns the ok-payload {@code binding-sets} unmarshalled into rows. On
     * err, throws {@link IOException} carrying the err string.
     */
    public List<WitValueMarshaller.Row> evaluate(final Node... args) throws IOException {
        final WitValue result = (WitValue) instance.invokeWit(
                "evaluate", WitValueMarshaller.toWitArgs(args));
        final WitValue ok = unwrapOk(result);
        return WitValueMarshaller.bindingSetsFromWit(ok);
    }

    /**
     * Feed one row into the aggregate accumulator.
     */
    public void aggregateStep(final Node[] args, final long multiplicity) throws IOException {
        final WitValue result = (WitValue) instance.invokeWit(
                "aggregate-step",
                WitValueMarshaller.toWitArgs(args),
                WitU64.of(multiplicity));
        unwrapOk(result);
    }

    /**
     * Finalise the aggregation and return the accumulated binding-sets.
     */
    public List<WitValueMarshaller.Row> aggregateFinish() throws IOException {
        final WitValue result = (WitValue) instance.invokeWit("aggregate-finish");
        return WitValueMarshaller.bindingSetsFromWit(unwrapOk(result));
    }

    /**
     * Invoke the component's {@code doc} export. WIT declares it as
     * {@code doc: func() -> binding-sets} (no result wrapping).
     */
    public List<WitValueMarshaller.Row> doc() {
        final WitValue result = (WitValue) instance.invokeWit("doc");
        return WitValueMarshaller.bindingSetsFromWit(result);
    }

    private static WitValue unwrapOk(final WitValue result) throws IOException {
        if (!(result instanceof WitResult)) {
            throw new IOException("Unexpected component return type: "
                    + (result == null ? "null" : result.getClass().getName()));
        }
        final WitResult wr = (WitResult) result;
        if (wr.isErr()) {
            throw new IOException(wr.getErr()
                    .map(v -> ((WitString) v).getValue())
                    .orElse("component returned err with no payload"));
        }
        return wr.getOk().orElse(null);
    }

    private static byte[] readAll(final URL url) throws IOException {
        final URLConnection conn = url.openConnection();
        conn.setConnectTimeout(240000);
        conn.setReadTimeout(240000);
        conn.connect();
        try (java.io.InputStream in = conn.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (component != null) component.close();
        if (engine != null) engine.close();
        instance = null;
        component = null;
        engine = null;
        closed = true;
    }
}
