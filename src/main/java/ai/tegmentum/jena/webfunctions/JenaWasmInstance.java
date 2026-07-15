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
import java.util.Optional;
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
    /**
     * Resolved auto-detected entry-point name, cached per URL so we only
     * enumerate the component's top-level function exports on the first
     * invocation. Explicit {@code InvokeSpec.entryPoint} overrides bypass
     * this cache entirely.
     */
    private static final ConcurrentHashMap<URL, String> ENTRY_POINT_CACHE =
            new ConcurrentHashMap<>();

    private final URL wasmUrl;
    private ComponentInstance instance;
    private boolean closed;

    public JenaWasmInstance(final URL wasmUrl) throws IOException {
        this.wasmUrl = wasmUrl;
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
            // v0.5.0 additive imports — sink-open / sink-execute /
            // sink-close for out-of-graph destinations (SQLite in v0.5), plus
            // a signature-changed execute-update that drops the bindings arg.
            // The other six imports are identical to v0.4.0 and re-registered
            // under the @0.5.0 instance name so guests targeting v0.5 link
            // cleanly.
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.5.0#execute-query",
                HostCallbacks.executeQuery());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.5.0#callback-depth",
                HostCallbacks.callbackDepth());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.5.0#execute-update",
                HostCallbacks.executeUpdateV05());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.5.0#prepare-query",
                HostCallbacks.prepareQuery());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.5.0#run-prepared",
                HostCallbacks.runPrepared());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.5.0#follow-predicate",
                HostCallbacks.followPredicate());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.5.0#invoke-wasm",
                HostCallbacks.invokeWasm());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.5.0#sink-open",
                HostCallbacks.sinkOpen());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.5.0#sink-execute",
                HostCallbacks.sinkExecute());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.5.0#sink-close",
                HostCallbacks.sinkClose());
            // v0.6.0 additive imports — execute-query-with-bindings unlocks
            // wf_pipeline v3's typed binding-set propagation: a step's row
            // grid is handed to the next SPARQL step as a substrate-native
            // VALUES splice, not stringified into VALUES text. Additive
            // registration: guests targeting v0.3.x .. v0.5.x continue to
            // link against their own interface instance above.
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.6.0#execute-query",
                HostCallbacks.executeQuery());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.6.0#execute-query-with-bindings",
                HostCallbacks.executeQueryWithBindings());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.6.0#callback-depth",
                HostCallbacks.callbackDepth());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.6.0#execute-update",
                HostCallbacks.executeUpdateV05());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.6.0#prepare-query",
                HostCallbacks.prepareQuery());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.6.0#run-prepared",
                HostCallbacks.runPrepared());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.6.0#follow-predicate",
                HostCallbacks.followPredicate());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.6.0#invoke-wasm",
                HostCallbacks.invokeWasm());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.6.0#sink-open",
                HostCallbacks.sinkOpen());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.6.0#sink-execute",
                HostCallbacks.sinkExecute());
            linker.addWitHostFunction(
                "stardog:webfunction/host@0.6.0#sink-close",
                HostCallbacks.sinkClose());
        }
        // wf:fulltext/host@0.1.0 — one import, `http-post-json`. The
        // wf_fulltext guest declares its own WIT world (versioned under
        // wf:fulltext, not stardog:webfunction), so this binds independently
        // of the callback-enabled flag: the flag is about re-entering the
        // graph, this import reaches an external fulltext backend. Guests
        // that never import wf:fulltext see no change in behaviour — the
        // wasm engine only pulls what the component's imports section names.
        linker.addWitHostFunction(
            "wf:fulltext/host@0.1.0#http-post-json",
            HostCallbacks.httpPostJson());
        // wf:document/host@1.3.0 — same shape (`http-post-json(url, body)
        // -> result<string, string>`) as wf:fulltext's, but under a
        // separately-versioned interface for the wf_document guest. The
        // guest uses it both for Manticore search calls and for Sirix
        // storage-backend fetches, so a document-mode SERVICE dispatch
        // needs this linker binding to instantiate at all. Additive —
        // guests that never import wf:document see no change in behaviour.
        linker.addWitHostFunction(
            "wf:document/host@1.3.0#http-post-json",
            HostCallbacks.httpPostJson());
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
     * Delegates to {@link #evaluate(String, Node...)} with a null override,
     * i.e. the substrate's default auto-detect (prefer {@code evaluate},
     * fall back to a single top-level function export).
     */
    public List<WitValueMarshaller.Row> evaluate(final Node... args) throws IOException {
        return evaluate(null, args);
    }

    /**
     * Invoke the component's exported function with the given arguments.
     * When {@code entryPointOverride} is non-null it is used verbatim;
     * otherwise the substrate resolves it per the standard order:
     * <ol>
     *   <li>Prefer {@code evaluate} — the substrate-wide default, every
     *       existing guest exports it.</li>
     *   <li>Fall back to a single top-level function export
     *       (e.g. wf_fulltext exports {@code search}).</li>
     *   <li>Otherwise error, listing the visible exports.</li>
     * </ol>
     * Mirrors {@code oxigraph-wf/src/wf_call.rs::resolve_entry_point}.
     */
    public List<WitValueMarshaller.Row> evaluate(final String entryPointOverride,
                                                 final Node... args) throws IOException {
        final String entry = resolveEntryPoint(entryPointOverride);
        // Marshal callsite args to whatever the export actually wants.
        // Legacy `evaluate(list<value>)` guests still take a single
        // packed WitList; multi-param guests like wf_fulltext's
        // `search(backend-url: string, index: string, query: string,
        // opts: query-opts)` need N typed positional values coerced from
        // the callsite Node[] per the introspected signature.
        final Object[] callArgs = marshalTypedArgs(entry, args);
        final WitValue result = (WitValue) instance.invokeWit(entry, callArgs);
        final WitValue ok = unwrapOk(result);
        return WitValueMarshaller.bindingSetsFromWit(ok);
    }

    /**
     * Look up the resolved entry-point's WIT signature via the wasmtime4j
     * component-type introspection API and marshal the callsite Nodes to
     * whatever positional shape the guest actually declared.
     *
     * <p>Two shapes are recognised:
     *
     * <ol>
     *   <li>The historical {@code evaluate(list<value>)} — one parameter
     *       whose type is a list. The Nodes are packed into a single
     *       {@link ai.tegmentum.wasmtime4j.wit.WitList} and returned as
     *       a length-1 {@code Object[]}, matching the current call
     *       convention.</li>
     *   <li>N typed positional parameters (wf_fulltext's {@code search}
     *       is the motivating case). Each Node's lexical form is
     *       extracted and coerced to the target WIT primitive; record /
     *       option / list targets parse the lexical form as JSON.</li>
     * </ol>
     *
     * <p>Type introspection is best-effort: when the underlying provider
     * doesn't expose typed export items (webassembly4j's public
     * {@code Component} API returns interface names only), we fall
     * through to the legacy packed shape. Guests that need multi-arg
     * dispatch use the wasmtime4j provider today, which does expose the
     * typed items — see {@code JniComponentImpl::componentType}.
     */
    private Object[] marshalTypedArgs(final String entry, final Node[] args) throws IOException {
        final Optional<ai.tegmentum.wasmtime4j.component.ComponentItemInfo.ComponentFuncInfo> info =
                lookupFuncInfo(entry);
        // No typed info available → fall back to the historical
        // packed `list<value>` convention.
        if (!info.isPresent()) {
            return new Object[] { WitValueMarshaller.toWitArgs(args) };
        }
        final List<ai.tegmentum.wasmtime4j.component.ComponentItemInfo.NamedType> params =
                info.get().params();
        // Historical `list<value>` shape: one param, list-typed. Every
        // stardog:webfunction guest before wf_fulltext used this ABI.
        if (params.size() == 1
                && params.get(0).type().getType()
                        == ai.tegmentum.wasmtime4j.component.ComponentType.LIST) {
            return new Object[] { WitValueMarshaller.toWitArgs(args) };
        }
        // Positional-limit fold: mirrors oxigraph-wf's
        // `maybe_fold_positional_limit`. An explicit
        // `wf:partial(<WASM>, ..., <limit>, <opts_json>)` callsite ships
        // one more positional arg than a guest whose trailing param is
        // an opts record where `limit` lives inside that record (the
        // wf_document `search-opts` shape — memo §04). The URL-sugar
        // rewrite path already hoists positional `limit` into opts_json
        // (7fc1f4c); the explicit `wf:partial` dispatch must do the
        // equivalent here so both callsites reach the guest with the
        // same 5-arg shape.
        final Node[] foldedArgs = maybeFoldPositionalLimit(params, args);
        if (params.size() != foldedArgs.length) {
            throw new IOException("arg count mismatch for `" + entry + "`: guest expects "
                    + params.size() + " positional params, callsite supplied " + foldedArgs.length);
        }
        final Object[] out = new Object[params.size()];
        for (int i = 0; i < params.size(); i++) {
            try {
                out[i] = coerceNodeToWit(
                        foldedArgs[i],
                        params.get(i).type());
            } catch (RuntimeException e) {
                throw new IOException("arg " + i + " of `" + entry + "`: " + e.getMessage(), e);
            }
        }
        return out;
    }

    /**
     * Detect the `wf:partial(..., <limit>, <opts_json>)` callsite that
     * supplies one more positional arg than the guest's typed signature
     * declares, and fold the extra scalar into the trailing opts record
     * so the marshaller can proceed with the guest's canonical shape.
     *
     * <p>Trigger conditions (all must hold):
     * <ul>
     *   <li>{@code args.length == params.size() + 1} — exactly one extra arg</li>
     *   <li>The last param is a record with a {@code limit} field of numeric or
     *       option&lt;numeric&gt; shape</li>
     *   <li>The second-to-last arg is a numeric lexical form (parseable as long)</li>
     *   <li>The last arg is a literal that parses as a JSON object</li>
     * </ul>
     *
     * <p>When triggered: merge the positional limit into the opts JSON
     * (an explicit `limit` in the opts wins over the positional — same
     * `or_insert_with` semantics as the URL-sugar path), rebuild the
     * args array with the positional limit dropped and the opts slot
     * replaced with the merged JSON literal.
     *
     * <p>When any condition fails: return the input unchanged. The
     * downstream arg-count check produces the honest error.
     *
     * <p>Mirrors oxigraph-wf's `maybe_fold_positional_limit`.
     */
    static Node[] maybeFoldPositionalLimit(
            final List<ai.tegmentum.wasmtime4j.component.ComponentItemInfo.NamedType> params,
            final Node[] args) {
        if (args.length != params.size() + 1) {
            return args;
        }
        if (params.isEmpty()) {
            return args;
        }
        final ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor lastParam =
                params.get(params.size() - 1).type();
        if (lastParam.getType() != ai.tegmentum.wasmtime4j.component.ComponentType.RECORD) {
            return args;
        }
        final java.util.Map<String, ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor> fields =
                lastParam.getRecordFields();
        final ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor limitField = fields.get("limit");
        if (limitField == null || !isNumericOrOptionNumeric(limitField)) {
            return args;
        }
        final int limitIdx = args.length - 2;
        final int optsIdx = args.length - 1;
        final long limitVal;
        try {
            // lexicalOf throws IllegalArgumentException for unsupported
            // Node kinds; Long.parseLong throws its subclass
            // NumberFormatException. Catch the parent — a single
            // catch covers both without triggering javac's multi-catch
            // subclass warning.
            limitVal = Long.parseLong(lexicalOf(args[limitIdx]));
        } catch (IllegalArgumentException e) {
            return args;
        }
        final String optsLex;
        try {
            optsLex = lexicalOf(args[optsIdx]);
        } catch (IllegalArgumentException e) {
            return args;
        }
        final String merged = mergePositionalLimitIntoOptsJson(optsLex, limitVal);
        if (merged == null) {
            return args;
        }
        final Node[] out = new Node[params.size()];
        for (int i = 0, w = 0; i < args.length; i++) {
            if (i == limitIdx) {
                continue;
            }
            if (i == optsIdx) {
                out[w++] = org.apache.jena.graph.NodeFactory.createLiteralString(merged);
            } else {
                out[w++] = args[i];
            }
        }
        return out;
    }

    private static boolean isNumericOrOptionNumeric(
            final ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor d) {
        final ai.tegmentum.wasmtime4j.component.ComponentType kind = d.getType();
        if (kind == ai.tegmentum.wasmtime4j.component.ComponentType.OPTION) {
            return isNumericOrOptionNumeric(d.getOptionType());
        }
        switch (kind) {
            case S8: case U8: case S16: case U16:
            case S32: case U32: case S64: case U64:
                return true;
            default:
                return false;
        }
    }

    /**
     * Pure JSON merge: parse {@code optsLex} as an object, insert
     * {@code "limit": <value>} if not already present, re-serialize.
     * Returns {@code null} when the input isn't a JSON object or the
     * re-serialize fails. An explicit `limit` key in the opts blob
     * wins over the positional (matches the URL-sugar path's
     * `or_insert_with` semantics). Split out for unit-testability.
     * Mirrors oxigraph-wf's helper of the same shape.
     */
    static String mergePositionalLimitIntoOptsJson(final String optsLex, final long limit) {
        // jena-arq's `JSON.parseAny` can throw both `JsonException` on
        // syntax errors and — on some inputs like an empty string — a
        // `NullPointerException` from its underlying tokenizer. Catch
        // `RuntimeException` so both branches decline the fold cleanly
        // instead of leaking through to the caller.
        try {
            final org.apache.jena.atlas.json.JsonValue parsed =
                    org.apache.jena.atlas.json.JSON.parseAny(optsLex);
            if (parsed == null || !parsed.isObject()) {
                return null;
            }
            final org.apache.jena.atlas.json.JsonObject obj = parsed.getAsObject();
            if (!obj.hasKey("limit")) {
                obj.put("limit", org.apache.jena.atlas.json.JsonNumber.value(limit));
            }
            return obj.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Ask the underlying wasmtime4j component for the exported func's
     * type info. Returns empty when the provider doesn't expose typed
     * items (the api-layer {@link ComponentInstance} keeps only names)
     * — callers handle that as a fall-through to the legacy shape.
     */
    private Optional<ai.tegmentum.wasmtime4j.component.ComponentItemInfo.ComponentFuncInfo>
            lookupFuncInfo(final String exportName) {
        final Optional<ai.tegmentum.wasmtime4j.component.ComponentInstance> nativeInstance =
                instance.unwrap(ai.tegmentum.wasmtime4j.component.ComponentInstance.class);
        if (!nativeInstance.isPresent()) return Optional.empty();
        try {
            final ai.tegmentum.wasmtime4j.component.ComponentTypeInfo typeInfo =
                    nativeInstance.get().getComponent().componentType();
            final Optional<ai.tegmentum.wasmtime4j.component.ComponentItemInfo> item =
                    typeInfo.getExportItem(exportName);
            if (!item.isPresent()) return Optional.empty();
            if (item.get() instanceof ai.tegmentum.wasmtime4j.component.ComponentItemInfo.ComponentFuncInfo funcInfo) {
                return Optional.of(funcInfo);
            }
            return Optional.empty();
        } catch (ai.tegmentum.wasmtime4j.exception.WasmException e) {
            return Optional.empty();
        }
    }

    /**
     * Coerce a Jena {@link Node} to a WIT value matching {@code target}.
     * The Node's lexical form (URI text, blank label, or literal
     * lexical form) is the source; numeric/bool targets parse it,
     * record/option/list targets treat it as a JSON payload.
     */
    private static Object coerceNodeToWit(
            final Node node,
            final ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor target) {
        final String lex = lexicalOf(node);
        return coerceLexicalToWit(lex, target);
    }

    private static String lexicalOf(final Node node) {
        if (node.isURI()) return node.getURI();
        if (node.isBlank()) return node.getBlankNodeLabel();
        if (node.isLiteral()) return node.getLiteralLexicalForm();
        throw new IllegalArgumentException("unsupported Node kind for typed-arg marshalling: " + node);
    }

    private static Object coerceLexicalToWit(
            final String lex,
            final ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor target) {
        final ai.tegmentum.wasmtime4j.component.ComponentType kind = target.getType();
        switch (kind) {
            case STRING:
                return witStringUnchecked(lex);
            case BOOL:
                return ai.tegmentum.wasmtime4j.wit.WitBool.of(Boolean.parseBoolean(lex));
            case S8:  return ai.tegmentum.wasmtime4j.wit.WitS8.of(Byte.parseByte(lex));
            case U8:  return ai.tegmentum.wasmtime4j.wit.WitU8.of(Byte.parseByte(lex));
            case S16: return ai.tegmentum.wasmtime4j.wit.WitS16.of(Short.parseShort(lex));
            case U16: return ai.tegmentum.wasmtime4j.wit.WitU16.of(Short.parseShort(lex));
            case S32: return ai.tegmentum.wasmtime4j.wit.WitS32.of(Integer.parseInt(lex));
            case U32: return ai.tegmentum.wasmtime4j.wit.WitU32.of(Integer.parseInt(lex));
            case S64: return ai.tegmentum.wasmtime4j.wit.WitS64.of(Long.parseLong(lex));
            case U64: return ai.tegmentum.wasmtime4j.wit.WitU64.of(Long.parseLong(lex));
            case F32: return ai.tegmentum.wasmtime4j.wit.WitFloat32.of(Float.parseFloat(lex));
            case F64: return ai.tegmentum.wasmtime4j.wit.WitFloat64.of(Double.parseDouble(lex));
            case CHAR: {
                if (lex.length() != 1) {
                    throw new IllegalArgumentException("char: expected exactly one code unit, got `" + lex + "`");
                }
                try {
                    return ai.tegmentum.wasmtime4j.wit.WitChar.of(lex.charAt(0));
                } catch (ai.tegmentum.wasmtime4j.exception.ValidationException e) {
                    throw new IllegalArgumentException("char: invalid code point in `" + lex + "`", e);
                }
            }
            default: {
                // Non-primitive: parse the lexical form as JSON and rebuild.
                final org.apache.jena.atlas.json.JsonValue json = parseJson(lex);
                return jsonToWit(json, target);
            }
        }
    }

    private static ai.tegmentum.wasmtime4j.wit.WitString witStringUnchecked(final String s) {
        try {
            return ai.tegmentum.wasmtime4j.wit.WitString.of(s);
        } catch (ai.tegmentum.wasmtime4j.exception.ValidationException e) {
            throw new IllegalArgumentException("invalid UTF-8 for WIT string: " + s, e);
        }
    }

    /**
     * Parse the lexical form as JSON via jena-arq's built-in JSON stack
     * — no extra codec dependency, and jena-arq is already a transitive
     * dep of this plugin.
     */
    private static org.apache.jena.atlas.json.JsonValue parseJson(final String lex) {
        try {
            return org.apache.jena.atlas.json.JSON.parseAny(lex);
        } catch (org.apache.jena.atlas.json.JsonException e) {
            throw new IllegalArgumentException("expected JSON payload, got `" + lex + "`: " + e.getMessage(), e);
        }
    }

    private static ai.tegmentum.wasmtime4j.wit.WitValue jsonToWit(
            final org.apache.jena.atlas.json.JsonValue json,
            final ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor target) {
        final ai.tegmentum.wasmtime4j.component.ComponentType kind = target.getType();
        switch (kind) {
            case BOOL:
                return ai.tegmentum.wasmtime4j.wit.WitBool.of(json.getAsBoolean().value());
            case S8:  return ai.tegmentum.wasmtime4j.wit.WitS8.of((byte) json.getAsNumber().value().intValue());
            case U8:  return ai.tegmentum.wasmtime4j.wit.WitU8.of((byte) (json.getAsNumber().value().intValue() & 0xff));
            case S16: return ai.tegmentum.wasmtime4j.wit.WitS16.of((short) json.getAsNumber().value().intValue());
            case U16: return ai.tegmentum.wasmtime4j.wit.WitU16.of((short) (json.getAsNumber().value().intValue() & 0xffff));
            case S32: return ai.tegmentum.wasmtime4j.wit.WitS32.of(json.getAsNumber().value().intValue());
            case U32: return ai.tegmentum.wasmtime4j.wit.WitU32.of(json.getAsNumber().value().intValue());
            case S64: return ai.tegmentum.wasmtime4j.wit.WitS64.of(json.getAsNumber().value().longValue());
            case U64: return ai.tegmentum.wasmtime4j.wit.WitU64.of(json.getAsNumber().value().longValue());
            case F32: return ai.tegmentum.wasmtime4j.wit.WitFloat32.of(json.getAsNumber().value().floatValue());
            case F64: return ai.tegmentum.wasmtime4j.wit.WitFloat64.of(json.getAsNumber().value().doubleValue());
            case STRING: return witStringUnchecked(json.getAsString().value());
            case OPTION: {
                final ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor inner =
                        target.getOptionType();
                // WitOption.some/none demand the OPTION-wrapped WitType, not the
                // inner type — passing the inner triggers extractInnerType's
                // "Type must be an option type" guard. `witTypeOf(target)` wraps
                // `option<inner>` correctly (see the OPTION case below).
                final ai.tegmentum.wasmtime4j.wit.WitType optionTy = witTypeOf(target);
                if (json.isNull()) {
                    return ai.tegmentum.wasmtime4j.wit.WitOption.none(optionTy);
                }
                return ai.tegmentum.wasmtime4j.wit.WitOption.some(
                        optionTy,
                        jsonToWit(json, inner));
            }
            case LIST: {
                final ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor elem =
                        target.getElementType();
                final org.apache.jena.atlas.json.JsonArray arr = json.getAsArray();
                if (arr.isEmpty()) {
                    return ai.tegmentum.wasmtime4j.wit.WitList.empty(witTypeOf(elem));
                }
                final java.util.List<ai.tegmentum.wasmtime4j.wit.WitValue> out = new ArrayList<>(arr.size());
                for (org.apache.jena.atlas.json.JsonValue item : arr) out.add(jsonToWit(item, elem));
                return ai.tegmentum.wasmtime4j.wit.WitList.of(out);
            }
            case RECORD: {
                // Accommodate a bare-scalar lexical form (e.g.
                // `wf:partial(..., "waterproof", 10)` where the guest's
                // 4th param is a query-opts record with `limit: int`,
                // `fields: list<string>`, and `highlight: bool`).
                // Policy mirrors the Oxigraph dispatcher
                // (oxigraph-wf/src/wf_call.rs::json_to_val):
                //
                //   * If exactly one non-optional field's type accepts
                //     the bare scalar's shape (int → int, bool → bool,
                //     string → string, array → list<_>) we slot it there
                //     and default-synthesize the other non-optional
                //     fields (empty list, false, "", 0/0.0). Option
                //     fields default to None as before.
                //   * If more than one field matches, we throw with
                //     both candidates named — the substrate does not
                //     guess between structurally-indistinguishable
                //     slots.
                //   * A missing non-optional field on an explicit
                //     JSON-object call path still throws — synthesis
                //     only fires on the bare-arg wrap path.
                final java.util.Map<String, ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor> fields =
                        target.getRecordFields();
                final org.apache.jena.atlas.json.JsonObject obj;
                final boolean synthMissing;
                if (json.isObject()) {
                    obj = json.getAsObject();
                    synthMissing = false;
                } else if (json.isNull()) {
                    throw new IllegalArgumentException("expected JSON object, got null");
                } else {
                    final Object placed = placeBareArgIntoRecord(json, fields);
                    obj = new org.apache.jena.atlas.json.JsonObject();
                    obj.put((String) placed, json);
                    synthMissing = true;
                }
                final ai.tegmentum.wasmtime4j.wit.WitRecord.Builder b =
                        ai.tegmentum.wasmtime4j.wit.WitRecord.builder();
                for (java.util.Map.Entry<String, ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor> e : fields.entrySet()) {
                    final String name = e.getKey();
                    final ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor fieldTy = e.getValue();
                    final org.apache.jena.atlas.json.JsonValue fieldJson = obj.get(name);
                    if ((fieldJson == null || fieldJson.isNull())
                            && fieldTy.getType() == ai.tegmentum.wasmtime4j.component.ComponentType.OPTION) {
                        // Pass the OPTION-wrapped type, not the inner type — same
                        // reason as the OPTION branch above.
                        b.field(name, ai.tegmentum.wasmtime4j.wit.WitOption.none(
                                witTypeOf(fieldTy)));
                    } else if (fieldJson == null && synthMissing) {
                        b.field(name, defaultValFor(fieldTy));
                    } else if (fieldJson == null) {
                        throw new IllegalArgumentException(
                                "record missing required field `" + name + "`");
                    } else {
                        b.field(name, jsonToWit(fieldJson, fieldTy));
                    }
                }
                return b.build();
            }
            default:
                throw new IllegalArgumentException(
                        "no JSON→WIT coercion implemented for target kind " + kind);
        }
    }

    /**
     * Bare-arg placement into a record. Picks the sole non-optional
     * field whose type accepts the bare JSON scalar (by shape: int →
     * int/float, bool → bool, string → string, array → list). Zero or
     * multiple matches throw with a specific message so operators see
     * which candidates were considered.
     *
     * <p>Returns the field name string. Kept as a static helper so
     * {@link RecordCoercionPolicy} tests exercise it without needing a
     * live component instance.
     */
    static String placeBareArgIntoRecord(
            final org.apache.jena.atlas.json.JsonValue bare,
            final java.util.Map<String, ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor> fields) {
        final RecordCoercionPolicy.JsonShape scalar = RecordCoercionPolicy.jsonShapeOf(bare);
        final java.util.List<java.util.Map.Entry<String, ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor>> nonOptional =
                new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor> e : fields.entrySet()) {
            if (e.getValue().getType() != ai.tegmentum.wasmtime4j.component.ComponentType.OPTION) {
                nonOptional.add(e);
            }
        }
        final java.util.List<String> candidates = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor> e : nonOptional) {
            if (RecordCoercionPolicy.shapeAccepts(
                    RecordCoercionPolicy.fieldShapeOf(e.getValue().getType()), scalar)) {
                candidates.add(e.getKey());
            }
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.isEmpty()) {
            final java.util.List<String> nonOptNames = new java.util.ArrayList<>();
            for (java.util.Map.Entry<String, ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor> e : nonOptional) {
                nonOptNames.add(e.getKey() + ": " + e.getValue().getType().name().toLowerCase(java.util.Locale.ROOT));
            }
            throw new IllegalArgumentException(
                    "bare " + scalar + " does not match any non-optional field of record "
                    + "(non-optional fields: [" + String.join(", ", nonOptNames) + "])");
        }
        throw new IllegalArgumentException(
                "bare arg is ambiguous - matches multiple non-optional fields ("
                + String.join(", ", candidates) + "); pass an explicit JSON object to disambiguate");
    }

    /**
     * Default-synth a WitValue for a target descriptor. Used when the
     * bare-arg placement path slotted the arg into one field and needs
     * to fill the other non-optional fields with something sensible.
     * Empty list, false, "", 0/0.0. Records / tuples / variants are
     * not synthesized — those need a callsite-provided value.
     */
    static ai.tegmentum.wasmtime4j.wit.WitValue defaultValFor(
            final ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor ty) {
        final ai.tegmentum.wasmtime4j.component.ComponentType kind = ty.getType();
        switch (kind) {
            case BOOL: return ai.tegmentum.wasmtime4j.wit.WitBool.of(false);
            case S8:  return ai.tegmentum.wasmtime4j.wit.WitS8.of((byte) 0);
            case U8:  return ai.tegmentum.wasmtime4j.wit.WitU8.of((byte) 0);
            case S16: return ai.tegmentum.wasmtime4j.wit.WitS16.of((short) 0);
            case U16: return ai.tegmentum.wasmtime4j.wit.WitU16.of((short) 0);
            case S32: return ai.tegmentum.wasmtime4j.wit.WitS32.of(0);
            case U32: return ai.tegmentum.wasmtime4j.wit.WitU32.of(0);
            case S64: return ai.tegmentum.wasmtime4j.wit.WitS64.of(0L);
            case U64: return ai.tegmentum.wasmtime4j.wit.WitU64.of(0L);
            case F32: return ai.tegmentum.wasmtime4j.wit.WitFloat32.of(0.0f);
            case F64: return ai.tegmentum.wasmtime4j.wit.WitFloat64.of(0.0);
            case STRING: return witStringUnchecked("");
            case LIST:
                return ai.tegmentum.wasmtime4j.wit.WitList.empty(witTypeOf(ty.getElementType()));
            case OPTION:
                // Pass the OPTION-wrapped type, not the inner type — see
                // jsonToWit's OPTION branch for the same reasoning.
                return ai.tegmentum.wasmtime4j.wit.WitOption.none(witTypeOf(ty));
            default:
                throw new IllegalArgumentException(
                        "no default-synth value for target kind " + kind
                        + " - bare-arg record coercion cannot fill this field automatically");
        }
    }

    /**
     * Convert a {@link ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor}
     * to the {@link ai.tegmentum.wasmtime4j.wit.WitType} shape needed
     * by the WIT value builders. Only the surface we actually build
     * against (primitives + option + list + record) is implemented;
     * exotic targets fall through with an error so the failure is loud.
     */
    private static ai.tegmentum.wasmtime4j.wit.WitType witTypeOf(
            final ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor d) {
        final ai.tegmentum.wasmtime4j.component.ComponentType kind = d.getType();
        switch (kind) {
            case BOOL: return ai.tegmentum.wasmtime4j.wit.WitType.createBool();
            case S8: return ai.tegmentum.wasmtime4j.wit.WitType.createS8();
            case U8: return ai.tegmentum.wasmtime4j.wit.WitType.createU8();
            case S16: return ai.tegmentum.wasmtime4j.wit.WitType.createS16();
            case U16: return ai.tegmentum.wasmtime4j.wit.WitType.createU16();
            case S32: return ai.tegmentum.wasmtime4j.wit.WitType.createS32();
            case U32: return ai.tegmentum.wasmtime4j.wit.WitType.createU32();
            case S64: return ai.tegmentum.wasmtime4j.wit.WitType.createS64();
            case U64: return ai.tegmentum.wasmtime4j.wit.WitType.createU64();
            case F32: return ai.tegmentum.wasmtime4j.wit.WitType.createFloat32();
            case F64: return ai.tegmentum.wasmtime4j.wit.WitType.createFloat64();
            case CHAR: return ai.tegmentum.wasmtime4j.wit.WitType.createChar();
            case STRING: return ai.tegmentum.wasmtime4j.wit.WitType.createString();
            case OPTION:
                return ai.tegmentum.wasmtime4j.wit.WitType.option(witTypeOf(d.getOptionType()));
            case LIST:
                return ai.tegmentum.wasmtime4j.wit.WitType.list(witTypeOf(d.getElementType()));
            case RECORD: {
                final java.util.Map<String, ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor> fields =
                        d.getRecordFields();
                final java.util.LinkedHashMap<String, ai.tegmentum.wasmtime4j.wit.WitType> out =
                        new java.util.LinkedHashMap<>();
                for (java.util.Map.Entry<String, ai.tegmentum.wasmtime4j.component.ComponentTypeDescriptor> e : fields.entrySet()) {
                    out.put(e.getKey(), witTypeOf(e.getValue()));
                }
                final String name = d.getName().orElse("record");
                return ai.tegmentum.wasmtime4j.wit.WitType.record(name, out);
            }
            default:
                throw new IllegalArgumentException(
                        "no WitType equivalent implemented for component-type kind " + kind);
        }
    }

    /**
     * Well-known primary export names, in preference order. Mirrors
     * {@code oxigraph-wf/src/wf_call.rs::WELL_KNOWN_ENTRY_POINTS}. Used
     * as the step-3 heuristic in {@link #resolveEntryPoint(String, List,
     * String)} when a guest ships no {@code evaluate} — e.g. wf_fulltext
     * exports {@code search} alongside {@code insert-batch} and
     * {@code delete-batch}, and the query dispatch is the SPARQL-facing
     * surface.
     */
    static final List<String> WELL_KNOWN_ENTRY_POINTS =
            List.of("search", "execute", "run", "dispatch");

    /**
     * Resolve which exported function this instance should invoke.
     *
     * <p>Resolution order (mirrors
     * {@code oxigraph-wf/src/wf_call.rs::resolve_entry_point}):
     * <ol>
     *   <li>Caller-supplied {@code override} — used verbatim.</li>
     *   <li>{@code evaluate} — the substrate default. Every stardog:webfunction
     *       guest exports it.</li>
     *   <li>A well-known primary export from
     *       {@link #WELL_KNOWN_ENTRY_POINTS} (in order). Covers domain
     *       WIT worlds like wf:fulltext that export {@code search}
     *       alongside admin/mutation entry points.</li>
     *   <li>A single top-level function export. Retained for
     *       single-export guests whose WIT world names its export off
     *       the well-known list.</li>
     *   <li>Otherwise throw, listing the visible exports so the caller can
     *       pick one via {@code InvokeSpec.entryPoint}.</li>
     * </ol>
     * The auto-detected name is memoised per URL — overrides bypass the cache.
     */
    public String resolveEntryPoint(final String override) {
        if (override != null && !override.isEmpty()) {
            return override;
        }
        return ENTRY_POINT_CACHE.computeIfAbsent(this.wasmUrl, u ->
                resolveEntryPoint(null, instance.exportedFunctions(), u.toString()));
    }

    /**
     * Pure resolution — same 5-step semantics as
     * {@link #resolveEntryPoint(String)} but without any component
     * interaction. Exposed at package scope so tests can exercise the
     * ambiguous-multi-export path without a matching fixture.
     */
    static String resolveEntryPoint(final String override,
                                    final List<String> topLevelExports,
                                    final String urlForErrors) {
        if (override != null && !override.isEmpty()) {
            return override;
        }
        if (topLevelExports.contains("evaluate")) {
            return "evaluate";
        }
        for (String candidate : WELL_KNOWN_ENTRY_POINTS) {
            if (topLevelExports.contains(candidate)) {
                return candidate;
            }
        }
        if (topLevelExports.isEmpty()) {
            throw new IllegalStateException(
                    "component at " + urlForErrors
                    + " has no function exports at the top level");
        }
        if (topLevelExports.size() == 1) {
            return topLevelExports.get(0);
        }
        throw new IllegalStateException(
                "component at " + urlForErrors
                + " has multiple function exports " + topLevelExports
                + " and no `evaluate` or well-known primary export ("
                + String.join(", ", WELL_KNOWN_ENTRY_POINTS)
                + ") — specify one via InvokeSpec.entryPoint");
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
        ENTRY_POINT_CACHE.clear();
        synchronized (ENGINE_LOCK) {
            if (SHARED_ENGINE != null) {
                SHARED_ENGINE.close();
                SHARED_ENGINE = null;
            }
        }
    }
}
