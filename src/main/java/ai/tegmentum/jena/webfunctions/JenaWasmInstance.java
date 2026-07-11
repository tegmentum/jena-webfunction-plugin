package ai.tegmentum.jena.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentResult;
import ai.tegmentum.wasmtime4j.component.ComponentVal;
import ai.tegmentum.wasmtime4j.component.ComponentVariant;
import ai.tegmentum.wasmtime4j.component.ConcurrentCall;
import ai.tegmentum.webassembly4j.api.Component;
import ai.tegmentum.webassembly4j.api.ComponentInstance;
import ai.tegmentum.webassembly4j.api.DefaultLinkingContext;
import ai.tegmentum.webassembly4j.api.Engine;
import ai.tegmentum.wasmtime4j.wit.WitResult;
import ai.tegmentum.wasmtime4j.wit.WitString;
import ai.tegmentum.wasmtime4j.wit.WitU64;
import ai.tegmentum.wasmtime4j.wit.WitValue;

import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Component-model WASM instance for the Jena binding. A single process-wide
 * {@link Engine} is lazily built the first time any URL is loaded (using
 * {@link WebFunctionConfig#buildEngine()} — note that {@code webfunctions.*}
 * system properties are frozen at that point); each URL's compiled
 * {@link Component} is cached in a static map keyed by URL, so repeated
 * {@code wf:call} invocations skip download + compilation and only pay
 * instantiation cost.
 *
 * <p>Each {@code JenaWasmInstance} owns a fresh {@link ComponentInstance}
 * created from the cached component; callers should {@link #close()} them
 * when done. The cached {@code Engine}/{@code Component} entries live for
 * the JVM's lifetime.
 */
public final class JenaWasmInstance implements Closeable {

    private static volatile Engine SHARED_ENGINE;
    private static final Object ENGINE_LOCK = new Object();
    private static final ConcurrentHashMap<URL, Component> COMPONENT_CACHE =
            new ConcurrentHashMap<>();

    private ComponentInstance instance;
    private boolean closed;

    public JenaWasmInstance(final URL wasmUrl) throws IOException {
        final Component component = componentFor(wasmUrl);
        final DefaultLinkingContext.Builder linker = DefaultLinkingContext.builder();
        if (WebFunctionConfig.callbackEnabled()) {
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.0#execute-query",
                HostCallbacks.executeQuery());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.0#callback-depth",
                HostCallbacks.callbackDepth());
            // v0.3.1 additive host imports — register alongside v0.3.0
            // so both worlds' components link cleanly.
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.1#execute-query",
                HostCallbacks.executeQuery());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.1#callback-depth",
                HostCallbacks.callbackDepth());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.1#execute-update",
                HostCallbacks.executeUpdate());
            // v0.3.2 additive imports — prepared queries.
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.2#execute-query",
                HostCallbacks.executeQuery());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.2#callback-depth",
                HostCallbacks.callbackDepth());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.2#execute-update",
                HostCallbacks.executeUpdate());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.2#prepare-query",
                HostCallbacks.prepareQuery());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.2#run-prepared",
                HostCallbacks.runPrepared());
            // v0.3.3 additive imports.
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.3#execute-query",
                HostCallbacks.executeQuery());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.3#callback-depth",
                HostCallbacks.callbackDepth());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.3#execute-update",
                HostCallbacks.executeUpdate());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.3#prepare-query",
                HostCallbacks.prepareQuery());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.3#run-prepared",
                HostCallbacks.runPrepared());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.3.3#follow-predicate",
                HostCallbacks.followPredicate());
            // v0.4.0 additive imports — invoke-wasm unlocks portable
            // higher-order combinators (wf_apply.wasm, wf_map.wasm) that
            // build on top of wf:call. The other six imports are identical
            // to v0.3.3 and re-registered against the v0.4.0 interface
            // instance name so guests targeting v0.4 link cleanly.
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.4.0#execute-query",
                HostCallbacks.executeQuery());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.4.0#callback-depth",
                HostCallbacks.callbackDepth());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.4.0#execute-update",
                HostCallbacks.executeUpdate());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.4.0#prepare-query",
                HostCallbacks.prepareQuery());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.4.0#run-prepared",
                HostCallbacks.runPrepared());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.4.0#follow-predicate",
                HostCallbacks.followPredicate());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.4.0#invoke-wasm",
                HostCallbacks.invokeWasm());
        }
        this.instance = component.instantiate(linker.build());
    }

    private static Engine sharedEngine() {
        Engine e = SHARED_ENGINE;
        if (e != null) return e;
        synchronized (ENGINE_LOCK) {
            if (SHARED_ENGINE == null) {
                final Engine built = WebFunctionConfig.buildEngine();
                if (!built.capabilities().supportsComponents()) {
                    built.close();
                    throw new IllegalStateException("engine '"
                            + built.info().engineId() + "' does not support components");
                }
                SHARED_ENGINE = built;
            }
            return SHARED_ENGINE;
        }
    }

    private static Component componentFor(final URL wasmUrl) throws IOException {
        Component cached = COMPONENT_CACHE.get(wasmUrl);
        if (cached != null) return cached;
        // computeIfAbsent inside a try — surface IOExceptions cleanly.
        final Engine engine = sharedEngine();
        try {
            return COMPONENT_CACHE.computeIfAbsent(wasmUrl, u -> {
                try {
                    return engine.loadComponent(readAll(u));
                } catch (IOException ioe) {
                    throw new UncheckedIOException(ioe);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * Invoke the component's {@code evaluate} export with the given arguments.
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
     * ComponentVal-based {@code evaluate} path via wasmtime4j's
     * {@code runConcurrent} + JSON codec. Structurally tolerant of the
     * mixed-variant binding rows that the {@link WitValue} deserializer
     * (used by {@link #evaluate}) currently rejects.
     *
     * <p><b>Limitation:</b> wasmtime4j's {@code runConcurrent} constructs
     * a fresh async linker on the Rust side that only wires WASI — the
     * host imports we register in the constructor are NOT visible to
     * the concurrent invocation. Callable only for wasms that don't use
     * the {@code stardog:webfunction/host} imports. Callable safely by
     * pure-computation guests once wasmtime4j exposes a hook to feed
     * host imports into the concurrent linker, so this method stays
     * here as a v0.1 escape hatch.
     */
    public List<WitValueMarshaller.Row> evaluateComponentVal(final Node... args) throws IOException {
        final List<ComponentVal> argList = new ArrayList<>(args.length);
        for (Node n : args) argList.add(nodeToComponentVal(n));

        final ConcurrentCall call = ConcurrentCall.of(
                "evaluate", ComponentVal.list(argList));
        // The webassembly4j-api ComponentInstance's runConcurrent is a
        // different abstraction (ConcurrentTask<T>) — unwrap to the
        // wasmtime4j-native instance to reach the JSON-based
        // runConcurrent(List<ConcurrentCall>) codec we actually want.
        final ai.tegmentum.wasmtime4j.component.ComponentInstance native_ =
                instance.unwrap(ai.tegmentum.wasmtime4j.component.ComponentInstance.class)
                        .orElseThrow(() -> new IOException(
                                "runConcurrent unavailable: instance is not the wasmtime4j "
                              + "provider (webassembly4j.engine.provider must be 'wasmtime')"));
        final List<List<ComponentVal>> results;
        try {
            results = native_.runConcurrent(java.util.Collections.singletonList(call));
        } catch (ai.tegmentum.wasmtime4j.exception.WasmException e) {
            throw new IOException("evaluate (runConcurrent) failed: " + e.getMessage(), e);
        }
        if (results.isEmpty() || results.get(0).isEmpty()) {
            throw new IOException("evaluate returned no values");
        }
        final ComponentVal outer = results.get(0).get(0);
        final ComponentResult res = outer.asResult();
        if (res.isErr()) {
            throw new IOException(res.getErr().map(ComponentVal::asString)
                    .orElse("component returned err with no payload"));
        }
        final ComponentVal ok = res.getOk().orElseThrow(() ->
                new IOException("evaluate: ok payload missing"));
        return bindingSetsFromComponentVal(ok);
    }

    /**
     * Invoke the component's {@code doc} export.
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
        // Instance is per-call — release only that. Engine + Component stay
        // cached for reuse across subsequent calls.
        instance = null;
        closed = true;
    }

    // ---- ComponentVal marshalling -----------------------------------------
    //
    // Same shape as HostCallbacks.encodeNode / decodeNode but kept private
    // to this class so the SERVICE handler stays a single dependency.

    private static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";

    private static ComponentVal nodeToComponentVal(final Node n) {
        if (n.isURI()) {
            return ComponentVal.variant("iri", ComponentVal.string(n.getURI()));
        }
        if (n.isBlank()) {
            return ComponentVal.variant("bnode", ComponentVal.string(n.getBlankNodeLabel()));
        }
        if (n.isLiteral()) {
            String datatype = n.getLiteralDatatypeURI();
            if (datatype == null || datatype.isEmpty()) datatype = XSD_STRING;
            final java.util.Map<String, ComponentVal> fields = new java.util.LinkedHashMap<>();
            fields.put("label", ComponentVal.string(n.getLiteralLexicalForm()));
            fields.put("datatype", ComponentVal.string(datatype));
            final String lang = n.getLiteralLanguage();
            fields.put("lang", (lang != null && !lang.isEmpty())
                    ? ComponentVal.some(ComponentVal.string(lang))
                    : ComponentVal.none());
            return ComponentVal.variant("literal", ComponentVal.record(fields));
        }
        throw new IllegalArgumentException("wf: unsupported Node kind: " + n);
    }

    private static Node componentValToNode(final ComponentVal v) {
        final ComponentVariant cv = v.asVariant();
        final String caseName = cv.getCaseName();
        final ComponentVal payload = cv.getPayload().orElse(null);
        switch (caseName) {
            case "iri":
                return NodeFactory.createURI(payload == null ? "" : payload.asString());
            case "bnode":
                return NodeFactory.createBlankNode(payload == null ? "" : payload.asString());
            case "literal": {
                if (payload == null) {
                    throw new IllegalStateException("wf: literal variant has no payload");
                }
                final java.util.Map<String, ComponentVal> fields = payload.asRecord();
                final String label = fields.get("label").asString();
                final String datatype = fields.get("datatype").asString();
                final java.util.Optional<ComponentVal> lang = fields.get("lang").asSome();
                if (lang.isPresent()) {
                    return NodeFactory.createLiteralLang(label, lang.get().asString());
                }
                final RDFDatatype dt = TypeMapper.getInstance().getSafeTypeByName(datatype);
                return NodeFactory.createLiteralDT(label, dt);
            }
            default:
                throw new IllegalStateException("wf: unknown value variant case: " + caseName);
        }
    }

    private static List<WitValueMarshaller.Row> bindingSetsFromComponentVal(final ComponentVal bs) {
        final java.util.Map<String, ComponentVal> fields = bs.asRecord();
        final List<String> vars = new ArrayList<>();
        for (ComponentVal e : fields.get("vars").asList()) vars.add(e.asString());
        final List<WitValueMarshaller.Row> out = new ArrayList<>();
        for (ComponentVal rowVal : fields.get("rows").asList()) {
            // Unbound columns stay null so BindingBuilder in the SERVICE
            // handler leaves them unbound (root row's `parent` is the
            // motivating case).
            final List<Node> byIndex = new ArrayList<>(java.util.Collections.nCopies(vars.size(), null));
            for (ComponentVal bindingVal : rowVal.asList()) {
                final java.util.Map<String, ComponentVal> b = bindingVal.asRecord();
                final String name = b.get("name").asString();
                final int idx = vars.indexOf(name);
                if (idx < 0) continue;
                byIndex.set(idx, componentValToNode(b.get("value")));
            }
            // WitValueMarshaller.Row is package-private-constructed; call
            // the marshaller's public helper via reflection would be
            // fragile — instead use a lightweight local subclass proxy
            // through a factory method.
            out.add(newRow(vars, byIndex));
        }
        return out;
    }

    private static WitValueMarshaller.Row newRow(final List<String> vars, final List<Node> values) {
        // WitValueMarshaller.Row's constructor is package-private and both
        // classes share the same package, so direct construction here
        // succeeds without reflection.
        return new WitValueMarshaller.Row(vars, values);
    }

    // ---- Test hooks --------------------------------------------------------

    /**
     * Purge all cached components + close the shared engine. Test-only.
     */
    static void resetCache() {
        COMPONENT_CACHE.forEach((k, c) -> c.close());
        COMPONENT_CACHE.clear();
        synchronized (ENGINE_LOCK) {
            if (SHARED_ENGINE != null) {
                SHARED_ENGINE.close();
                SHARED_ENGINE = null;
            }
        }
    }
}
