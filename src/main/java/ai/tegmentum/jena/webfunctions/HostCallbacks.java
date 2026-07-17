package ai.tegmentum.jena.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;
import ai.tegmentum.webassembly4j.api.WitHostFunction;

import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.QuerySolutionMap;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.impl.ResourceImpl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Host callbacks satisfying the v0.3.0 WIT world's {@code host} import interface:
 * {@code stardog:webfunction/host@0.3.0#execute-query} and
 * {@code stardog:webfunction/host@0.3.0#callback-depth}.
 *
 * <p>Same pattern as the RDF4J plugin: {@link ComponentVal} at the linker
 * boundary, not {@code WitValue}. Value marshalling adapts to Jena's
 * {@link Node} type. See {@link CallbackContext} for the ThreadLocal binding
 * mechanism.
 */
public final class HostCallbacks {

    private static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";

    private HostCallbacks() {}

    /**
     * v0.6 {@code execute-query-with-bindings: func(query: string,
     *  seed: binding-sets, max-rows: option<u32>) -> result<binding-sets, string>}.
     *
     * <p>Unlike {@link #executeQuery} — which pre-seeds a single row's worth
     * of scalar bindings — this accepts a full {@code binding-sets} record
     * (vars + rows) and splices it under the query's outermost projection as
     * a VALUES join. Mirrors Oxigraph's {@code run_query_with_seed} and gives
     * wf_pipeline v3's typed binding-set propagation a substrate-native path
     * that doesn't route through VALUES-text interpolation.
     *
     * <p>Missing cells become Jena UNDEF (null in the {@link
     * org.apache.jena.sparql.engine.binding.Binding}), matching SPARQL 1.1
     * VALUES semantics.
     */
    public static WitHostFunction executeQueryWithBindings() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: no context bound — WfCall must bind CallbackContext "
                    + "at the top of exec()")) };
            }
            try {
                final String sparql = ((ComponentVal) args[0]).asString();
                final ComponentVal seedVal = (ComponentVal) args[1];
                final Map<String, ComponentVal> seedFields = seedVal.asRecord();
                final ComponentVal varsField = seedFields.get("vars");
                final ComponentVal rowsField = seedFields.get("rows");
                if (varsField == null || rowsField == null) {
                    return new Object[] { ComponentVal.err(ComponentVal.string(
                        "wf execute-query-with-bindings: seed missing vars/rows field")) };
                }
                final List<org.apache.jena.sparql.core.Var> seedVars = new ArrayList<>();
                for (ComponentVal v : varsField.asList()) {
                    seedVars.add(org.apache.jena.sparql.core.Var.alloc(v.asString()));
                }
                final List<org.apache.jena.sparql.engine.binding.Binding> seedRows =
                        new ArrayList<>();
                for (ComponentVal rowVal : rowsField.asList()) {
                    final org.apache.jena.sparql.engine.binding.BindingBuilder bb =
                            org.apache.jena.sparql.engine.binding.BindingFactory.builder();
                    for (ComponentVal bindingVal : rowVal.asList()) {
                        final Map<String, ComponentVal> bf = bindingVal.asRecord();
                        final String name = bf.get("name").asString();
                        final Node n = decodeNode(bf.get("value"));
                        bb.add(org.apache.jena.sparql.core.Var.alloc(name), n);
                    }
                    seedRows.add(bb.build());
                }
                final int rowCap = decodeOptionalU32((ComponentVal) args[2]).orElseGet(ctx::maxRows);

                ctx.enter();
                try {
                    final ResultSet rs = ctx.executeSelectWithBindings(sparql, seedVars, seedRows);
                    return new Object[] { ComponentVal.ok(encodeBindingSets(rs, rowCap)) };
                } finally {
                    ctx.exit();
                }
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    /** {@code execute-query: func(sparql: string, bindings: list<binding>,
     *  max-rows: option<u32>) -> result<binding-sets, string>}. */
    public static WitHostFunction executeQuery() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: no context bound — WfCall must bind CallbackContext "
                    + "at the top of exec()")) };
            }
            try {
                final String sparql = ((ComponentVal) args[0]).asString();
                final QuerySolutionMap initial = decodeBindings((ComponentVal) args[1]);
                final int rowCap = decodeOptionalU32((ComponentVal) args[2]).orElseGet(ctx::maxRows);

                ctx.enter();
                try {
                    final ResultSet rs = ctx.executeSelect(sparql, initial);
                    return new Object[] { ComponentVal.ok(encodeBindingSets(rs, rowCap)) };
                } finally {
                    ctx.exit();
                }
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    /**
     * v0.4 {@code invoke-wasm: func(url: string, args: list<value>)
     * -> result<binding-sets, string>}.
     *
     * <p>Recursively invokes another wasm component identified by
     * {@code url}. The nested guest runs in a fresh
     * {@link JenaWasmInstance} — component instantiation is cached per
     * URL by wasmtime4j, so back-to-back invocations of the same URL
     * reuse the compiled component.
     *
     * <p>Depth accounting: the recursion counter is bumped around the
     * nested call so the host's callback-max-depth cap covers
     * invoke-wasm chains and a subsequent execute-query at the outer
     * level sees the correct depth on return.
     */
    public static WitHostFunction invokeWasm() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: invoke-wasm has no context bound — nested guest "
                    + "was reached from a code path that didn't bind CallbackContext")) };
            }
            try {
                final String url = ((ComponentVal) args[0]).asString();
                final ComponentVal argsList = (ComponentVal) args[1];
                final List<ComponentVal> inner = argsList.asList();
                final Node[] callArgs = new Node[inner.size()];
                for (int i = 0; i < inner.size(); i++) {
                    callArgs[i] = decodeNode(inner.get(i));
                }

                ctx.enter();
                try {
                    final JenaWasmInstance instance =
                            new JenaWasmInstance(new java.net.URL(url));
                    try {
                        final List<WitValueMarshaller.Row> rows = instance.evaluate(callArgs);
                        return new Object[] { ComponentVal.ok(encodeRows(rows, ctx.maxRows())) };
                    } finally {
                        instance.close();
                    }
                } finally {
                    ctx.exit();
                }
            } catch (Exception e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "invoke-wasm: " + (e.getMessage() == null ? e.toString() : e.getMessage()))) };
            }
        };
    }

    /**
     * Encode a {@link JenaWasmInstance#evaluate} result set (List of Rows
     * that already carry the shared {@code vars} list) into a WIT
     * {@code binding-sets} record. Companion to the {@link ResultSet}
     * overload above, for the invoke-wasm return path.
     */
    private static ComponentVal encodeRows(final List<WitValueMarshaller.Row> rows,
                                           final int rowCap) {
        final List<String> vars = rows.isEmpty() ? List.of() : rows.get(0).vars;
        final List<ComponentVal> varsVals = new ArrayList<>();
        for (String v : vars) varsVals.add(ComponentVal.string(v));

        final List<ComponentVal> rowVals = new ArrayList<>();
        int emitted = 0;
        for (WitValueMarshaller.Row row : rows) {
            if (emitted >= rowCap) break;
            final List<ComponentVal> bindings = new ArrayList<>();
            for (int i = 0; i < row.vars.size(); i++) {
                final Node n = row.values.get(i);
                if (n == null) continue;
                final Map<String, ComponentVal> bindingFields = new LinkedHashMap<>();
                bindingFields.put("name", ComponentVal.string(row.vars.get(i)));
                bindingFields.put("value", encodeNode(n));
                bindings.add(ComponentVal.record(bindingFields));
            }
            rowVals.add(ComponentVal.list(bindings));
            emitted++;
        }
        final Map<String, ComponentVal> bs = new LinkedHashMap<>();
        bs.put("vars", ComponentVal.list(varsVals));
        bs.put("rows", ComponentVal.list(rowVals));
        return ComponentVal.record(bs);
    }

    /** {@code callback-depth: func() -> u32}. */
    public static WitHostFunction callbackDepth() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            return new Object[] { ComponentVal.u32(ctx == null ? 0L : (long) ctx.depth()) };
        };
    }

    /** {@code follow-predicate: func(subject: value, predicate: value)
     *  -> result<list<value>, string>}  (v0.3.3). */
    public static WitHostFunction followPredicate() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: no context bound")) };
            }
            try {
                final Node subj = decodeNode((ComponentVal) args[0]);
                final Node pred = decodeNode((ComponentVal) args[1]);
                ctx.enter();
                try {
                    final java.util.List<Node> objs = ctx.followPredicate(subj, pred);
                    final java.util.List<ComponentVal> encoded =
                        new java.util.ArrayList<>(objs.size());
                    for (Node n : objs) encoded.add(encodeNode(n));
                    return new Object[] { ComponentVal.ok(ComponentVal.list(encoded)) };
                } finally {
                    ctx.exit();
                }
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    /** {@code prepare-query: func(sparql: string) -> result<u32, string>}
     *  (v0.3.2). */
    public static WitHostFunction prepareQuery() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: no context bound")) };
            }
            try {
                final String sparql = ((ComponentVal) args[0]).asString();
                return new Object[] { ComponentVal.ok(ComponentVal.u32((long) ctx.prepare(sparql))) };
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    /** {@code run-prepared: func(handle: u32, bindings: list<binding>,
     *  max-rows: option<u32>) -> result<binding-sets, string>}  (v0.3.2). */
    public static WitHostFunction runPrepared() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: no context bound")) };
            }
            try {
                final int handle = (int) ((ComponentVal) args[0]).asU32();
                final QuerySolutionMap initial = decodeBindings((ComponentVal) args[1]);
                final int rowCap = decodeOptionalU32((ComponentVal) args[2]).orElseGet(ctx::maxRows);
                ctx.enter();
                try {
                    final ResultSet rs = ctx.runPrepared(handle, initial);
                    return new Object[] { ComponentVal.ok(encodeBindingSets(rs, rowCap)) };
                } finally {
                    ctx.exit();
                }
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    /**
     * v0.5 {@code execute-update: func(update: string) -> result<_, string>}.
     *
     * <p>Signature difference from v0.3.1's execute-update — v0.5 drops the
     * bindings list. The guest is expected to inline any bound values before
     * calling. Parses via Jena's UpdateExec builder and applies against the
     * caller's Dataset (same {@link CallbackContext#executeUpdate} that
     * v0.3.1 uses, passed an empty QuerySolutionMap).
     */
    public static WitHostFunction executeUpdateV05() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: no context bound")) };
            }
            try {
                final String sparql = ((ComponentVal) args[0]).asString();
                ctx.enter();
                try {
                    ctx.executeUpdate(sparql, new org.apache.jena.query.QuerySolutionMap());
                    return new Object[] { ComponentVal.ok(null) };
                } finally {
                    ctx.exit();
                }
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    /**
     * v0.5 {@code sink-open: func(url: string) -> result<u32, string>}.
     *
     * <p>Parses the URL, dispatches on scheme (only {@code sqlite://} is
     * wired in v0.5), and stores the resulting connection in the frame's
     * sink table. The returned handle is the slot index.
     */
    public static WitHostFunction sinkOpen() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf sink-open: no context bound")) };
            }
            try {
                final String url = ((ComponentVal) args[0]).asString();
                final Sink sink = SinkOpen.open(url);
                final int handle = ctx.addSink(sink);
                return new Object[] { ComponentVal.ok(ComponentVal.u32((long) handle)) };
            } catch (Exception e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "sink-open: " + (e.getMessage() == null ? e.toString() : e.getMessage()))) };
            }
        };
    }

    /**
     * v0.5 {@code sink-execute: func(handle: u32, query: string, params: list<value>)
     *  -> result<binding-sets, string>}.
     *
     * <p>Looks up the connection by handle, delegates to the backend's
     * {@link Sink#execute} which does the WIT ↔ SQL param binding and
     * result-set encoding.
     */
    public static WitHostFunction sinkExecute() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf sink-execute: no context bound")) };
            }
            try {
                final int handle = (int) ((ComponentVal) args[0]).asU32();
                final String query = ((ComponentVal) args[1]).asString();
                final List<ComponentVal> params = ((ComponentVal) args[2]).asList();
                final Sink sink = ctx.getSink(handle);
                if (sink == null) {
                    return new Object[] { ComponentVal.err(ComponentVal.string(
                        "sink-execute: stale or closed handle " + handle)) };
                }
                final ComponentVal out = sink.execute(query, params);
                return new Object[] { ComponentVal.ok(out) };
            } catch (Exception e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "sink-execute: " + (e.getMessage() == null ? e.toString() : e.getMessage()))) };
            }
        };
    }

    /**
     * v0.5 {@code sink-close: func(handle: u32) -> result<_, string>}.
     *
     * <p>Explicit close is optional — {@link CallbackContext#unbindIfOutermost}
     * closes any leftover sinks when the outermost wf:call frame exits. Guests
     * that need to release a scarce resource (Postgres pool slot) early can
     * call this.
     */
    public static WitHostFunction sinkClose() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf sink-close: no context bound")) };
            }
            try {
                final int handle = (int) ((ComponentVal) args[0]).asU32();
                if (!ctx.closeSink(handle)) {
                    return new Object[] { ComponentVal.err(ComponentVal.string(
                        "sink-close: stale or already-closed handle " + handle)) };
                }
                return new Object[] { ComponentVal.ok(null) };
            } catch (Exception e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "sink-close: " + (e.getMessage() == null ? e.toString() : e.getMessage()))) };
            }
        };
    }

    /**
     * {@code wf:sagegraph/host@0.2.0#execute-query:
     *  func(sparql: string) -> result<string, string>}.
     *
     * <p>The wf_sagegraph guest imports this to issue k-hop neighborhood
     * SPARQL round-trips back into the engine hosting it. Unlike the
     * {@code stardog:webfunction/host} family's {@link #executeQuery} — which
     * hands back a WIT-encoded {@code binding-sets} record — this one returns
     * raw SPARQL 1.1 Results JSON as a string and lets the guest parse it.
     * Same shape wf_document / wf_fulltext expose via {@code http-post-json};
     * sagegraph just reaches the local engine instead of an external service.
     *
     * <p>Reuses {@link CallbackContext#executeSelect} — the same executor
     * behind the stardog:webfunction/host callbacks — then serializes with
     * Jena's {@link org.apache.jena.query.ResultSetFormatter#outputAsJSON}
     * for SELECT / CONSTRUCT / DESCRIBE / ASK all through the ResultSet
     * envelope that {@code executeSelect} already unifies on.
     */
    public static WitHostFunction sagegraphExecuteQuery() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: no context bound — WfCall must bind CallbackContext "
                    + "at the top of exec()")) };
            }
            try {
                final String sparql = ((ComponentVal) args[0]).asString();
                ctx.enter();
                try {
                    final ResultSet rs = ctx.executeSelect(sparql, new QuerySolutionMap());
                    final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                    org.apache.jena.query.ResultSetFormatter.outputAsJSON(buf, rs);
                    return new Object[] { ComponentVal.ok(ComponentVal.string(
                        buf.toString(java.nio.charset.StandardCharsets.UTF_8))) };
                } finally {
                    ctx.exit();
                }
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    /**
     * {@code wf:fulltext/host@0.1.0#http-post-json:
     *  func(url: string, body: string) -> result<string, string>}.
     *
     * <p>The wf_fulltext guest imports this to POST a JSON search request
     * to Manticore (OpenSearch as a follow-up per the wf-fulltext design
     * memo). Stateless — does not touch {@link CallbackContext}, does not
     * respect {@code webfunctions.callback.enabled} (that flag is about
     * re-entering the graph; this import reaches an external service).
     *
     * <p>Error contract, mirrored across every substrate engine:
     * <ul>
     *   <li>2xx: {@code Ok(response_body_verbatim)}</li>
     *   <li>non-2xx: {@code Err("HTTP <code>: <body>")}</li>
     *   <li>transport / URL / IO / interrupt: {@code Err("http transport: <details>")}</li>
     * </ul>
     * Timeout: 30 seconds — matches the oxigraph-wf substrate and
     * SirixSink's connect + read ceiling so a wf:call frame observes one
     * honest deadline regardless of destination.
     */
    public static WitHostFunction httpPostJson() {
        return args -> {
            try {
                final String url = ((ComponentVal) args[0]).asString();
                final String body = ((ComponentVal) args[1]).asString();
                return new Object[] { httpPostJsonImpl(url, body) };
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "http transport: " + (e.getMessage() == null ? e.toString() : e.getMessage()))) };
            }
        };
    }

    private static ComponentVal httpPostJsonImpl(final String url, final String body) {
        final java.net.URI uri;
        try {
            uri = java.net.URI.create(url);
        } catch (IllegalArgumentException iae) {
            return ComponentVal.err(ComponentVal.string(
                "http transport: url did not parse: " + iae.getMessage()));
        }
        final java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
        final java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(uri)
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                    body, java.nio.charset.StandardCharsets.UTF_8))
                .build();
        try {
            final java.net.http.HttpResponse<String> response = client.send(
                request, java.net.http.HttpResponse.BodyHandlers.ofString(
                    java.nio.charset.StandardCharsets.UTF_8));
            final int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return ComponentVal.ok(ComponentVal.string(response.body()));
            }
            return ComponentVal.err(ComponentVal.string(
                "HTTP " + status + ": " + response.body()));
        } catch (java.io.IOException ioe) {
            return ComponentVal.err(ComponentVal.string(
                "http transport: " + (ioe.getMessage() == null ? ioe.toString() : ioe.getMessage())));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return ComponentVal.err(ComponentVal.string("http transport: interrupted"));
        }
    }

    /**
     * {@code wf:embed/host@0.1.0#embed-text:
     *  func(text: string, model: string) -> result<list<f32>, string>}.
     *
     * <p>Substrate-side text-embedding host callback. Dispatches through
     * one of two paths, chosen at each call by the {@code WF_EMBED_SIDECAR_URL}
     * environment variable:
     *
     * <ul>
     *   <li><b>Env set → sidecar dispatch (Option B).</b> POST
     *       {@code {"text": ..., "model": ...}} to the configured URL,
     *       parse {@code {"embedding": [f32, ...]}} from the response, and
     *       return those bytes verbatim. When the sidecar is
     *       {@code fastembed-rs}-backed with the same on-disk model as the
     *       Rust engines' embedded {@code fastembed::TextEmbedding}, this
     *       gives cross-engine byte parity — the pinned
     *       {@code sagegraph_text_features} expected_bindings are
     *       fastembed-rs {@code bge-small-en} output; a fastembed-rs
     *       sidecar reproduces those bytes.</li>
     *   <li><b>Env unset → deterministic SHA-256-derived stub fallback.</b>
     *       Bit-for-bit identical output to the earlier Rust-engine stub
     *       landing (oxigraph-wf {@code d07c2d6}, qlever-wf-runtime
     *       {@code register_wf_embed_host_import} stub). Emits a one-shot
     *       warning on stderr so operators know the plugin fell back.
     *       Kept so JVM engines still resolve the wf:embed import even
     *       without a sidecar deployed; cross-engine byte parity does NOT
     *       hold against Rust engines' real fastembed output in this
     *       mode.</li>
     * </ul>
     *
     * <p>Stub algorithm — matches {@code embed_text_stub} in
     * {@code oxigraph-wf/src/host.rs}:
     * <ol>
     *   <li>Dispatch model → dim via {@link #embedModelCatalog()}
     *       (default 384 for unknown).</li>
     *   <li>Hash {@code model || 0x00 || text || 0x00 || counter_le}
     *       under SHA-256, incrementing {@code counter} until we have
     *       {@code dim * 4} bytes.</li>
     *   <li>Read each 4-byte window as a little-endian signed i32,
     *       map to {@code [-1, 1]} via {@code raw / (float) Integer.MAX_VALUE}.</li>
     *   <li>L2-normalise so cosine-similarity consumers see unit vectors.</li>
     * </ol>
     *
     * <p>Empty text → {@code Err("embed-text: text is empty")}. Sidecar
     * transport / parse errors → {@code Err("embed-text: <details>")}; the
     * fallback stub is only reached when the env var is unset (a
     * deliberately-configured sidecar returning HTTP 500 surfaces the 500
     * rather than being silently masked by stub bytes).
     */
    public static WitHostFunction embedText() {
        return args -> {
            try {
                final String text = ((ComponentVal) args[0]).asString();
                final String model = ((ComponentVal) args[1]).asString();
                if (text.isEmpty()) {
                    return new Object[] { ComponentVal.err(ComponentVal.string(
                        "embed-text: text is empty")) };
                }
                final String sidecarUrl = System.getenv(EMBED_SIDECAR_URL_ENV);
                final float[] vec;
                if (sidecarUrl != null && !sidecarUrl.isEmpty()) {
                    vec = embedTextViaSidecar(text, model, sidecarUrl);
                } else {
                    warnStubFallbackOnce();
                    vec = embedTextStub(text, model);
                }
                final List<ComponentVal> vals = new ArrayList<>(vec.length);
                for (float v : vec) {
                    vals.add(ComponentVal.f32(v));
                }
                return new Object[] { ComponentVal.ok(ComponentVal.list(vals)) };
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "embed-text: " + (e.getMessage() == null ? e.toString() : e.getMessage()))) };
            }
        };
    }

    /** Env var name for the {@link #embedText} sidecar dispatch URL. */
    static final String EMBED_SIDECAR_URL_ENV = "WF_EMBED_SIDECAR_URL";

    /**
     * One-shot gate for the "fell back to SHA-256 stub" warning. Wrapped
     * in an {@link java.util.concurrent.atomic.AtomicBoolean} so the log
     * line fires once per JVM regardless of how many wf:call frames hit
     * the fallback path.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean
        STUB_FALLBACK_WARNED = new java.util.concurrent.atomic.AtomicBoolean(false);

    private static void warnStubFallbackOnce() {
        if (STUB_FALLBACK_WARNED.compareAndSet(false, true)) {
            System.err.println(
                "wf:embed: " + EMBED_SIDECAR_URL_ENV + " unset — falling back to "
                + "deterministic SHA-256 stub. Cross-engine byte parity vs Rust "
                + "engines' real fastembed output does NOT hold in this mode. "
                + "Point " + EMBED_SIDECAR_URL_ENV + " at a fastembed-rs HTTP "
                + "sidecar to restore parity.");
        }
    }

    /**
     * POST {@code {"text": ..., "model": ...}} to the sidecar and parse
     * {@code {"embedding": [f32, ...]}} out of the response body. Any
     * transport / parse failure surfaces as a
     * {@link RuntimeException} so the outer {@link #embedText} envelope
     * turns it into a WIT {@code Err} rather than falling back to the
     * stub (see {@link #embedText} javadoc for the rationale).
     */
    static float[] embedTextViaSidecar(final String text, final String model,
                                       final String sidecarUrl) {
        final java.net.URI uri;
        try {
            uri = java.net.URI.create(sidecarUrl);
        } catch (IllegalArgumentException iae) {
            throw new IllegalStateException(
                "sidecar url did not parse: " + iae.getMessage(), iae);
        }
        final String body =
            "{\"text\":" + jsonEscape(text)
            + ",\"model\":" + jsonEscape(model) + "}";
        final java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
        final java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(uri)
                .timeout(java.time.Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                    body, java.nio.charset.StandardCharsets.UTF_8))
                .build();
        final java.net.http.HttpResponse<String> response;
        try {
            response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString(
                java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException ioe) {
            throw new IllegalStateException(
                "sidecar transport: " + (ioe.getMessage() == null ? ioe.toString() : ioe.getMessage()), ioe);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("sidecar transport: interrupted", ie);
        }
        final int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException(
                "sidecar HTTP " + status + ": " + response.body());
        }
        return parseEmbeddingArray(response.body());
    }

    /**
     * Extract the {@code "embedding"} array of floats from the sidecar's
     * JSON response. Deliberately minimal — we don't want to pull a JSON
     * library into this class just for one two-field payload. Handles the
     * common shapes: {@code {"embedding": [1.0, 2.0, ...]}}, tolerant of
     * whitespace and scientific notation. Anything else raises so the
     * caller can surface a WIT {@code Err} with the raw body prefix.
     */
    static float[] parseEmbeddingArray(final String responseBody) {
        final int keyIdx = responseBody.indexOf("\"embedding\"");
        if (keyIdx < 0) {
            throw new IllegalStateException(
                "sidecar response missing 'embedding' key: "
                + truncate(responseBody, 200));
        }
        final int openBracket = responseBody.indexOf('[', keyIdx);
        if (openBracket < 0) {
            throw new IllegalStateException(
                "sidecar response 'embedding' is not an array: "
                + truncate(responseBody, 200));
        }
        final int closeBracket = responseBody.indexOf(']', openBracket);
        if (closeBracket < 0) {
            throw new IllegalStateException(
                "sidecar response 'embedding' array is unterminated: "
                + truncate(responseBody, 200));
        }
        final String inner = responseBody.substring(openBracket + 1, closeBracket).trim();
        if (inner.isEmpty()) {
            return new float[0];
        }
        final String[] parts = inner.split(",");
        final float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException nfe) {
                throw new IllegalStateException(
                    "sidecar 'embedding'[" + i + "] not a f32: " + parts[i].trim(), nfe);
            }
        }
        return out;
    }

    private static String jsonEscape(final String s) {
        final StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String truncate(final String s, final int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * {@code wf:embed/host@0.1.0#list-models: func() -> list<string>}.
     *
     * <p>Enumerates the model names the substrate registration recognises.
     * Byte-identical to the Rust-side catalog so cross-engine calls to
     * this import see the same ordered list.
     */
    public static WitHostFunction embedListModels() {
        return args -> {
            final String[][] catalog = embedModelCatalog();
            final List<ComponentVal> vals = new ArrayList<>(catalog.length);
            for (String[] pair : catalog) {
                vals.add(ComponentVal.string(pair[0]));
            }
            return new Object[] { ComponentVal.list(vals) };
        };
    }

    /**
     * Model → embedding-dimension catalog for the wf:embed v0.1 stub.
     * Names + dims mirror {@code fastembed-rs} so a future swap to real
     * inference is a signature-compatible substitution. Held in lockstep
     * with {@code oxigraph-wf::host::embed_model_catalog}.
     */
    private static String[][] embedModelCatalog() {
        return new String[][] {
            {"bge-small-en",     "384"},
            {"all-MiniLM-L6-v2", "384"},
            {"bge-base-en",      "768"},
            {"nomic-embed-text", "768"},
            {"bge-large-en",     "1024"},
        };
    }

    /**
     * Deterministic SHA-256-derived embedding matching the Rust
     * {@code embed_text_stub} in {@code oxigraph-wf/src/host.rs} (commit
     * {@code d07c2d6}) so cross-engine byte parity holds.
     */
    static float[] embedTextStub(final String text, final String model) {
        int dim = 384;
        for (String[] pair : embedModelCatalog()) {
            if (pair[0].equalsIgnoreCase(model)) {
                dim = Integer.parseInt(pair[1]);
                break;
            }
        }
        final int bytesNeeded = Math.multiplyExact(dim, 4);
        final byte[] bytes = new byte[((bytesNeeded + 31) / 32) * 32];
        final byte[] modelBytes = model.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final byte[] textBytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int counter = 0;
        int offset = 0;
        final java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable in this JVM", e);
        }
        while (offset < bytesNeeded) {
            md.reset();
            md.update(modelBytes);
            md.update((byte) 0);
            md.update(textBytes);
            md.update((byte) 0);
            md.update((byte) (counter & 0xff));
            md.update((byte) ((counter >>> 8) & 0xff));
            md.update((byte) ((counter >>> 16) & 0xff));
            md.update((byte) ((counter >>> 24) & 0xff));
            final byte[] digest = md.digest();
            System.arraycopy(digest, 0, bytes, offset, digest.length);
            offset += digest.length;
            counter++;
        }
        final float[] out = new float[dim];
        for (int i = 0; i < dim; i++) {
            final int b0 = bytes[i * 4]     & 0xff;
            final int b1 = bytes[i * 4 + 1] & 0xff;
            final int b2 = bytes[i * 4 + 2] & 0xff;
            final int b3 = bytes[i * 4 + 3] & 0xff;
            final int raw = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
            out[i] = (float) raw / (float) Integer.MAX_VALUE;
        }
        // Rust reference: `let norm: f32 = out.iter().map(|x| x*x).sum::<f32>().sqrt();`
        // Mirror the f32 accumulation + f32 sqrt exactly — a
        // double-accumulated variant drifts a few ULP by dim 384 and
        // breaks the cross-engine byte-parity contract.
        float norm = 0.0f;
        for (float f : out) norm += f * f;
        norm = (float) Math.sqrt((double) norm);
        if (norm > 0.0f) {
            for (int i = 0; i < dim; i++) {
                out[i] = out[i] / norm;
            }
        }
        return out;
    }

    /** {@code execute-update: func(sparql: string, bindings: list<binding>)
     *  -> result<_, string>}  (v0.3.1). */
    public static WitHostFunction executeUpdate() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: no context bound")) };
            }
            try {
                final String sparql = ((ComponentVal) args[0]).asString();
                final QuerySolutionMap initial = decodeBindings((ComponentVal) args[1]);
                ctx.enter();
                try {
                    ctx.executeUpdate(sparql, initial);
                    return new Object[] { ComponentVal.ok(null) };
                } finally {
                    ctx.exit();
                }
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    // ---- marshalling -------------------------------------------------------

    /**
     * {@code list<binding>} — a ComponentVal list of {@code binding} records
     * with fields ("name", "value"). Jena's initial-binding shape is a
     * QuerySolutionMap keyed by var name.
     */
    private static QuerySolutionMap decodeBindings(final ComponentVal list) {
        final QuerySolutionMap qsm = new QuerySolutionMap();
        for (ComponentVal elem : list.asList()) {
            final Map<String, ComponentVal> fields = elem.asRecord();
            final String name = fields.get("name").asString();
            final Node rdfNode = decodeNode(fields.get("value"));
            qsm.add(name, ModelFactory.createDefaultModel().asRDFNode(rdfNode));
        }
        return qsm;
    }

    /** {@code variant value { iri | literal | bnode }} → Jena Node. */
    private static Node decodeNode(final ComponentVal variant) {
        final ai.tegmentum.wasmtime4j.component.ComponentVariant cv = variant.asVariant();
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
                final Map<String, ComponentVal> fields = payload.asRecord();
                final String label = fields.get("label").asString();
                final String datatype = fields.get("datatype").asString();
                final Optional<ComponentVal> lang = fields.get("lang").asSome();
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

    private static Optional<Integer> decodeOptionalU32(final ComponentVal option) {
        return option.asSome().map(v -> (int) v.asU32());
    }

    /**
     * Encode a {@link ResultSet} as {@code record binding-sets { vars: list<string>,
     * rows: list<list<binding>> }}. Materialises up to {@code rowCap} rows.
     */
    private static ComponentVal encodeBindingSets(final ResultSet rs, final int rowCap) {
        final List<String> vars = rs.getResultVars();
        final LinkedHashSet<String> varsSeen = new LinkedHashSet<>(vars);
        final List<ComponentVal> rows = new ArrayList<>();
        int rowsSeen = 0;
        while (rs.hasNext() && rowsSeen < rowCap) {
            final org.apache.jena.query.QuerySolution qs = rs.next();
            final List<ComponentVal> bindings = new ArrayList<>();
            for (String var : vars) {
                final RDFNode node = qs.get(var);
                if (node == null) continue;
                final Map<String, ComponentVal> bindingFields = new LinkedHashMap<>();
                bindingFields.put("name", ComponentVal.string(var));
                bindingFields.put("value", encodeNode(node.asNode()));
                bindings.add(ComponentVal.record(bindingFields));
            }
            rows.add(ComponentVal.list(bindings));
            rowsSeen++;
        }
        final List<ComponentVal> varsVals = new ArrayList<>();
        for (String v : varsSeen) varsVals.add(ComponentVal.string(v));

        final Map<String, ComponentVal> bs = new LinkedHashMap<>();
        bs.put("vars", ComponentVal.list(varsVals));
        bs.put("rows", ComponentVal.list(rows));
        return ComponentVal.record(bs);
    }

    /** Jena Node → {@code variant value { iri | literal | bnode }}. */
    private static ComponentVal encodeNode(final Node n) {
        if (n.isURI()) {
            return ComponentVal.variant("iri", ComponentVal.string(n.getURI()));
        }
        if (n.isBlank()) {
            return ComponentVal.variant("bnode", ComponentVal.string(n.getBlankNodeLabel()));
        }
        if (n.isLiteral()) {
            final String label = n.getLiteralLexicalForm();
            String datatype = n.getLiteralDatatypeURI();
            if (datatype == null || datatype.isEmpty()) datatype = XSD_STRING;
            final Map<String, ComponentVal> fields = new LinkedHashMap<>();
            fields.put("label", ComponentVal.string(label));
            fields.put("datatype", ComponentVal.string(datatype));
            final String lang = n.getLiteralLanguage();
            fields.put("lang", (lang != null && !lang.isEmpty())
                    ? ComponentVal.some(ComponentVal.string(lang))
                    : ComponentVal.none());
            return ComponentVal.variant("literal", ComponentVal.record(fields));
        }
        throw new IllegalArgumentException("wf: unsupported Node kind: " + n);
    }
}
