package ai.tegmentum.jena.webfunctions.conformance;

import ai.tegmentum.jena.webfunctions.WebFunctionInit;
import ai.tegmentum.jena.webfunctions.rewrite.AliasMap;
import ai.tegmentum.jena.webfunctions.rewrite.AliasRewriteState;
import ai.tegmentum.jena.webfunctions.rewrite.ConversionRegistry;
import ai.tegmentum.jena.webfunctions.rewrite.DocumentRegistry;
import ai.tegmentum.jena.webfunctions.rewrite.FederationRegistry;
import ai.tegmentum.jena.webfunctions.rewrite.FulltextRegistry;
import ai.tegmentum.jena.webfunctions.rewrite.InvokeRegistry;
import ai.tegmentum.jena.webfunctions.rewrite.RewritePipeline;
import ai.tegmentum.jena.webfunctions.rewrite.ShapeRegistry;
import ai.tegmentum.jena.webfunctions.rewrite.WebFunctionQueryEngine;
import ai.tegmentum.jena.webfunctions.rewrite.WfRelationalRegistry;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ResultSetStream;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingBuilder;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Command-line entrypoint for the cross-engine SPARQL conformance suite.
 *
 * <p>Loads a Turtle dataset into a fresh in-memory transactional
 * {@link DatasetGraph}, builds a {@link RewritePipeline.Context} from
 * optional JSON config files, executes a SELECT query through the
 * webfunction {@link WebFunctionQueryEngine}, and writes the SPARQL
 * Results JSON serialization to {@code stdout}. When the alias pass has
 * populated a per-query {@link AliasRewriteState} on the ARQ Context, the
 * result-set bindings are rewritten back to their alias form before
 * serialization so the caller sees the IRIs they mentioned.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * java -cp target/webfunction-*.jar \
 *   ai.tegmentum.jena.webfunctions.conformance.ConformanceMain \
 *   --data path/to/data.ttl --query path/to/query.sparql \
 *   [--alias-config alias.json] [--shape-config shape.json] \
 *   [--conversion-config conversion.json] [--partial-config partial.json] \
 *   [--fulltext-config fulltext.json] [--document-config document.json] \
 *   [--federation-config federation.json]
 * }</pre>
 *
 * <p>Exit code 0 on success; non-zero with an error line on stderr on
 * failure. When no config files are passed the pipeline is empty and the
 * runner behaves as a plain SPARQL passthrough.
 */
public final class ConformanceMain {

    private ConformanceMain() {}

    public static void main(final String[] args) {
        final int rc;
        try {
            rc = run(args, System.out, System.err);
        } catch (Throwable t) {
            System.err.println("conformance-main: " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(1);
            return;
        }
        if (rc != 0) System.exit(rc);
    }

    /**
     * Test-friendly entry point. Doesn't call {@link System#exit(int)};
     * exceptions propagate. Delegates to
     * {@link #run(String[], PrintStream, PrintStream)}; non-zero exit
     * codes (e.g. an invalid {@code --fulltext-config},
     * {@code --document-config}, or {@code --federation-config}) surface
     * as a {@link RuntimeException} so
     * callers that don't observe stderr still see the failure. The
     * config-error message itself is written to {@link System#err} by
     * the underlying runner.
     */
    public static void run(final String[] args, final PrintStream out) throws Exception {
        final int rc = run(args, out, System.err);
        if (rc != 0) {
            throw new RuntimeException("conformance-main exited with code " + rc);
        }
    }

    /**
     * Full entry point exposing stderr + a returned exit code so callers
     * (main, shell-out tests) can distinguish the config-error path from
     * the successful-run path without pattern-matching exception
     * messages.
     */
    public static int run(final String[] args, final PrintStream out, final PrintStream err) throws Exception {
        final Map<String, String> parsed = parseArgs(args);
        final Path dataPath = requirePathArg(parsed, "--data");
        final Path queryPath = requirePathArg(parsed, "--query");
        final Path aliasCfg = optionalPathArg(parsed, "--alias-config");
        final Path shapeCfg = optionalPathArg(parsed, "--shape-config");
        final Path conversionCfg = optionalPathArg(parsed, "--conversion-config");
        final Path partialCfg = optionalPathArg(parsed, "--partial-config");
        final Path fulltextCfg = optionalPathArg(parsed, "--fulltext-config");
        final Path documentCfg = optionalPathArg(parsed, "--document-config");
        final Path federationCfg = optionalPathArg(parsed, "--federation-config");

        // Fulltext registry is loaded independently of the rewrite
        // pipeline: it stores config only, and the filter-fold rewrite
        // pass that consumes it is a separate follow-up. Presence of
        // the flag exercises the parser + validation surface end-to-end;
        // absence keeps the runner identical to the pre-fulltext build.
        final FulltextRegistry fulltextRegistry;
        try {
            fulltextRegistry = fulltextCfg == null
                    ? FulltextRegistry.empty()
                    : FulltextRegistry.loadFromJson(fulltextCfg);
        } catch (Exception e) {
            err.println("fulltext config error: " + e.getMessage());
            return 2;
        }
        // Diagnostic on stderr so the parity harness (and the
        // ConformanceMainFulltextTest) can assert the registry
        // populated correctly without smuggling state through a static.
        if (fulltextCfg != null) {
            err.println("loaded " + fulltextRegistry.size()
                    + " fulltext index(es) from " + fulltextCfg);
        }
        // Reference the registry to keep the (currently pipeline-agnostic)
        // load side-effect from being dead-code-eliminated by future
        // reviewers. The filter-fold rewrite is a follow-up pass; when
        // it lands, this local moves into pipelineCtx.
        assert fulltextRegistry != null;

        // Document registry (v0.2 wf_document companion). Same load-and-
        // report shape as the fulltext registry above; not wired into the
        // rewrite pipeline because v0.2 dispatches wf_document exclusively
        // through explicit SERVICE ?svc. The flag surface exercises the
        // parser + validation only.
        final DocumentRegistry documentRegistry;
        try {
            documentRegistry = documentCfg == null
                    ? DocumentRegistry.empty()
                    : DocumentRegistry.loadFromJson(documentCfg);
        } catch (Exception e) {
            err.println("document config error: " + e.getMessage());
            return 2;
        }
        if (documentCfg != null) {
            err.println("loaded " + documentRegistry.size()
                    + " document(s) from " + documentCfg);
        }
        assert documentRegistry != null;

        // Federation registry (wf_federation v0.1). Load-and-report shape
        // matches the fulltext / document registries; wired into the
        // rewrite pipeline below so a `--federation-config` invocation
        // actually rewrites BGPs into SERVICE calls.
        //
        // v0.2 probe-mode wiring: every loaded registry is chained
        // through `withProbeFn(defaultAskProbeFn())` so registries whose
        // JSON sets `probe_mode: true` actually issue ASK queries at
        // plan time. Empty registries pay nothing (the probe path never
        // fires when `probe_mode = false`), so we install the fn
        // unconditionally to keep the boot path simple.
        final FederationRegistry federationRegistry;
        try {
            federationRegistry = federationCfg == null
                    ? FederationRegistry.empty()
                    : FederationRegistry.loadFromJson(federationCfg)
                            .withProbeFn(defaultAskProbeFn());
        } catch (Exception e) {
            err.println("federation config error: " + e.getMessage());
            return 2;
        }
        if (federationCfg != null) {
            err.println("loaded " + federationRegistry.size()
                    + " federation source(s) from " + federationCfg);
        }

        // wf-relational sidecar registry (wf-relational memo §04). Reads
        // the same federation-config file but only captures the
        // per-source `relational` descriptor block that
        // FederationRegistry deliberately drops. Empty registry is a
        // valid state (either no config at all or no wf-relational
        // sources in the config); WfRelationalRewrite short-circuits
        // when empty.
        final WfRelationalRegistry wfRelationalRegistry;
        try {
            wfRelationalRegistry = federationCfg == null
                    ? WfRelationalRegistry.empty()
                    : WfRelationalRegistry.loadFromJson(federationCfg);
        } catch (Exception e) {
            err.println("wf-relational config error: " + e.getMessage());
            return 2;
        }
        if (federationCfg != null && !wfRelationalRegistry.isEmpty()) {
            err.println("loaded " + wfRelationalRegistry.size()
                    + " wf-relational source(s) from " + federationCfg);
        }

        // The subsystem service file usually wires this up automatically,
        // but calling directly is idempotent and covers callers that
        // bypass the standard classloader-driven init (e.g., embedded
        // in-process test harnesses).
        WebFunctionInit.register();

        final AliasMap aliasMap = aliasCfg == null
                ? AliasMap.empty() : loadAliasMap(aliasCfg);
        final ShapeRegistry shapeRegistry = shapeCfg == null
                ? ShapeRegistry.empty() : loadShapeRegistry(shapeCfg);
        final ConversionRegistry conversionRegistry = conversionCfg == null
                ? ConversionRegistry.empty() : loadConversionRegistry(conversionCfg);
        final String wfFetchUrl = partialCfg == null
                ? null : loadWfFetchUrl(partialCfg);
        final InvokeRegistry invokeRegistry = new InvokeRegistry();

        final RewritePipeline.Context pipelineCtx = new RewritePipeline.Context(
                invokeRegistry, conversionRegistry, aliasMap, shapeRegistry,
                fulltextRegistry, documentRegistry, federationRegistry,
                wfRelationalRegistry, wfFetchUrl);
        // Install on the ARQ global context; QueryExecution copies it into
        // the per-query context, so the engine factory's accept() sees
        // PIPELINE_SYMBOL and modifyOp writes ALIAS_STATE_SYMBOL back onto
        // the same per-query context (readable via qe.getContext()).
        WebFunctionQueryEngine.installGlobal(pipelineCtx);

        final DatasetGraph dsg = DatasetGraphFactory.createTxnMem();
        RDFDataMgr.read(dsg, dataPath.toUri().toString());
        final Dataset dataset = DatasetFactory.wrap(dsg);

        final String queryText = Files.readString(queryPath);
        final Query query = QueryFactory.create(queryText);

        try (QueryExecution qe = QueryExecutionFactory.create(query, dataset)) {
            // Dispatch on query shape. SELECT is the historical happy
            // path; CONSTRUCT / DESCRIBE surface their triples as an
            // s/p/o tuple-shape ResultSet so the conformance runner (which
            // canonicalises SPARQL Results JSON only) can compare without
            // a graph-shape-aware code path. ASK bypasses the ResultSet
            // machinery entirely and writes the SPARQL Results JSON
            // boolean shape directly.
            if (query.isSelectType()) {
                final ResultSet rs = qe.execSelect();
                final AliasRewriteState state = readAliasState(qe);
                final ResultSet wrapped = wrapWithAliasRewrite(rs, state);
                ResultSetFormatter.outputAsJSON(out, wrapped);
            } else if (query.isAskType()) {
                final boolean answer = qe.execAsk();
                writeAskAsJson(out, answer);
            } else if (query.isConstructType() || query.isDescribeType()) {
                final Iterator<Triple> triples = query.isConstructType()
                        ? qe.execConstructTriples()
                        : qe.execDescribeTriples();
                final AliasRewriteState state = readAliasState(qe);
                final ResultSet wrapped = triplesAsSpoResultSet(triples, state);
                ResultSetFormatter.outputAsJSON(out, wrapped);
            } else {
                throw new IllegalArgumentException(
                        "conformance-main: unsupported query shape (queryType="
                                + query.queryType() + ")");
            }
            out.flush();
        }
        return 0;
    }

    /**
     * Pull the alias-rewrite state off the per-query ARQ Context. The
     * modifyOp pass populates {@link WebFunctionQueryEngine#ALIAS_STATE_SYMBOL}
     * during plan construction, so this returns a populated state once the
     * execution has been kicked off (via execSelect / execConstructTriples
     * / execDescribeTriples / execAsk) and an inactive state otherwise.
     */
    private static AliasRewriteState readAliasState(final QueryExecution qe) {
        final Object stateObj = qe.getContext().get(
                WebFunctionQueryEngine.ALIAS_STATE_SYMBOL);
        return stateObj instanceof AliasRewriteState s
                ? s : AliasRewriteState.inactive();
    }

    /**
     * Wrap an {@link Iterator} of {@link Triple}s as a {@link ResultSet}
     * with three columns — {@code ?s}, {@code ?p}, {@code ?o} — one row
     * per triple. Runs each node through {@link AliasRewriteState}
     * so output IRIs come back under whatever alias the query mentioned,
     * matching the wrapping the SELECT path already performs.
     */
    private static ResultSet triplesAsSpoResultSet(final Iterator<Triple> triples,
                                                   final AliasRewriteState state) {
        final Var vs = Var.alloc("s");
        final Var vp = Var.alloc("p");
        final Var vo = Var.alloc("o");
        final List<Var> vars = List.of(vs, vp, vo);
        final Iterator<Binding> transformed = new Iterator<>() {
            @Override public boolean hasNext() { return triples.hasNext(); }
            @Override public Binding next() {
                final Triple t = triples.next();
                final BindingBuilder bb = Binding.builder();
                bb.add(vs, state.isActive() ? state.rewriteNode(t.getSubject())   : t.getSubject());
                bb.add(vp, state.isActive() ? state.rewriteNode(t.getPredicate()) : t.getPredicate());
                bb.add(vo, state.isActive() ? state.rewriteNode(t.getObject())    : t.getObject());
                return bb.build();
            }
        };
        return ResultSetStream.create(vars, transformed);
    }

    /**
     * Emit the SPARQL 1.1 Results JSON boolean serialization —
     * {@code {"head":{},"boolean":true|false}} — for an ASK result. Hand-
     * rolled rather than routed through {@link ResultSetFormatter} because
     * the Jena formatter API takes different arg shapes for tuple vs
     * boolean results and we only need the two-key wire form here.
     */
    private static void writeAskAsJson(final PrintStream out, final boolean answer) {
        out.print("{\"head\":{},\"boolean\":");
        out.print(answer ? "true" : "false");
        out.print('}');
    }

    // ---------------------------------------------------------------
    // ResultSet rewrite plumbing
    // ---------------------------------------------------------------

    /**
     * Wrap {@code rs} in a lazy iterator that pipes each row's binding
     * through {@link AliasRewriteState#rewriteNode(Node)}. If the state
     * is inactive the source ResultSet is returned unchanged so
     * passthrough pays effectively zero cost.
     */
    private static ResultSet wrapWithAliasRewrite(final ResultSet rs,
                                                  final AliasRewriteState state) {
        if (!state.isActive()) {
            return rs;
        }
        final List<String> vars = rs.getResultVars();
        final List<Var> jenaVars = new ArrayList<>(vars.size());
        for (String v : vars) jenaVars.add(Var.alloc(v));

        final Iterator<Binding> transformed = new Iterator<>() {
            @Override public boolean hasNext() { return rs.hasNext(); }
            @Override public Binding next() {
                final Binding src = rs.nextBinding();
                final BindingBuilder bb = Binding.builder();
                src.forEach((v, n) -> bb.add(v, state.rewriteNode(n)));
                return bb.build();
            }
        };
        return ResultSetStream.create(jenaVars, transformed);
    }

    // ---------------------------------------------------------------
    // Config file loaders (Jena's atlas.json — no extra dependency)
    // ---------------------------------------------------------------

    private static AliasMap loadAliasMap(final Path p) throws Exception {
        final JsonObject obj = JSON.parse(Files.readString(p));
        if (!obj.hasKey("aliases")) return AliasMap.empty();
        final JsonObject aliases = obj.get("aliases").getAsObject();
        final Map<String, String> m = new HashMap<>();
        for (String k : aliases.keys()) {
            m.put(k, aliases.get(k).getAsString().value());
        }
        return AliasMap.of(m);
    }

    private static ShapeRegistry loadShapeRegistry(final Path p) throws Exception {
        final JsonObject obj = JSON.parse(Files.readString(p));
        if (!obj.hasKey("shapes")) return ShapeRegistry.empty();
        final JsonArray shapes = obj.get("shapes").getAsArray();
        final List<ShapeRegistry.ShapeEntry> entries = new ArrayList<>();
        for (JsonValue v : shapes) {
            if (!v.isObject()) continue;
            final JsonObject s = v.getAsObject();
            final String name = s.get("name").getAsString().value();
            // Prefer explicit descriptor_json, otherwise synthesize one
            // from the flat fields (anchor_class + predicates_to_columns)
            // so simple test fixtures stay ergonomic.
            final String descriptorJson = s.hasKey("descriptor_json")
                    ? s.get("descriptor_json").getAsString().value()
                    : synthesizeDescriptor(s);
            entries.add(ShapeRegistry.parseEntry(name, descriptorJson));
        }
        return ShapeRegistry.of(entries);
    }

    private static String synthesizeDescriptor(final JsonObject s) {
        final JsonObject descriptor = new JsonObject();
        if (s.hasKey("anchor_class")) {
            final JsonObject anchor = new JsonObject();
            anchor.put("class", s.get("anchor_class").getAsString().value());
            descriptor.put("anchor", anchor);
        }
        final JsonArray columns = new JsonArray();
        if (s.hasKey("predicates_to_columns")) {
            final JsonObject p2c = s.get("predicates_to_columns").getAsObject();
            for (String pred : p2c.keys()) {
                final JsonObject col = new JsonObject();
                col.put("predicate", pred);
                col.put("name", p2c.get(pred).getAsString().value());
                columns.add(col);
            }
        }
        descriptor.put("columns", columns);
        return descriptor.toString();
    }

    private static ConversionRegistry loadConversionRegistry(final Path p) throws Exception {
        final JsonObject obj = JSON.parse(Files.readString(p));
        if (!obj.hasKey("rules")) return ConversionRegistry.empty();
        final JsonArray rules = obj.get("rules").getAsArray();
        final List<ConversionRegistry.RawRule> raw = new ArrayList<>();
        for (JsonValue v : rules) {
            if (!v.isObject()) continue;
            final JsonObject r = v.getAsObject();
            raw.add(new ConversionRegistry.RawRule(
                    r.get("target_predicate").getAsString().value(),
                    r.get("source_predicate").getAsString().value(),
                    r.get("expression").getAsString().value()));
        }
        return ConversionRegistry.of(raw);
    }

    private static String loadWfFetchUrl(final Path p) throws Exception {
        final JsonObject obj = JSON.parse(Files.readString(p));
        if (!obj.hasKey("wf_fetch_url")) return null;
        return obj.get("wf_fetch_url").getAsString().value();
    }

    /**
     * Default v0.2 ASK-probe function &mdash; issues
     * {@code ASK { ?s <predicate> ?o }} against {@code source.endpoint()}
     * over HTTP using the JDK's built-in {@link java.net.http.HttpClient}
     * (same class {@code HostCallbacks.httpPostJsonImpl} uses, so
     * substrate-wide error/timeout behaviour stays consistent).
     * Only meaningful for {@code SPARQL} / {@code HTTP_SPARQL} sources
     * &mdash; other source types (wf-search / wf-fetch / etc.) don't
     * speak SPARQL so the probe returns {@code false} for them (the
     * static predicate list stays the source of truth for wf-* sources).
     *
     * <p>Kept in {@code ConformanceMain} rather than in
     * {@link FederationRegistry} to respect the v0.2 landing's DNT
     * fence on the registry module. Mirrors
     * {@code oxigraph-wf::federation_registry::default_ask_probe_fn}
     * so all four engines exhibit identical probe semantics under an
     * identical registry JSON.
     */
    private static FederationRegistry.ProbeFn defaultAskProbeFn() {
        return (src, predicateIri) -> {
            final FederationRegistry.SourceType t = src.sourceType();
            if (t != FederationRegistry.SourceType.SPARQL
                    && t != FederationRegistry.SourceType.HTTP_SPARQL) {
                return false;
            }
            final String query = "ASK { ?s <" + predicateIri + "> ?o }";
            final java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build();
            final java.net.http.HttpRequest request = java.net.http.HttpRequest
                    .newBuilder(java.net.URI.create(src.endpoint()))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("Content-Type", "application/sparql-query")
                    .header("Accept", "application/sparql-results+json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                            query, java.nio.charset.StandardCharsets.UTF_8))
                    .build();
            final java.net.http.HttpResponse<String> response = client.send(
                    request, java.net.http.HttpResponse.BodyHandlers.ofString(
                            java.nio.charset.StandardCharsets.UTF_8));
            final int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new java.io.IOException(
                        "probe HTTP " + status + ": " + response.body());
            }
            // Minimal SPARQL Results JSON parse — {"head":{}, "boolean": true|false}.
            final JsonObject obj = JSON.parse(response.body());
            if (!obj.hasKey("boolean")) return false;
            return obj.get("boolean").getAsBoolean().value();
        };
    }

    // ---------------------------------------------------------------
    // Arg parsing
    // ---------------------------------------------------------------

    private static Map<String, String> parseArgs(final String[] args) {
        final Map<String, String> m = new HashMap<>();
        int i = 0;
        while (i < args.length) {
            final String a = args[i];
            if (!a.startsWith("--")) {
                throw new IllegalArgumentException("unexpected positional argument: " + a);
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("missing value for " + a);
            }
            m.put(a, args[i + 1]);
            i += 2;
        }
        return m;
    }

    private static Path requirePathArg(final Map<String, String> m, final String k) {
        final String v = m.get(k);
        if (v == null) {
            throw new IllegalArgumentException("missing required arg " + k);
        }
        return Paths.get(v);
    }

    private static Path optionalPathArg(final Map<String, String> m, final String k) {
        final String v = m.get(k);
        return v == null ? null : Paths.get(v);
    }
}
