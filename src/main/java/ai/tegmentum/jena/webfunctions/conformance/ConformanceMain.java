package ai.tegmentum.jena.webfunctions.conformance;

import ai.tegmentum.jena.webfunctions.WebFunctionInit;
import ai.tegmentum.jena.webfunctions.rewrite.AliasMap;
import ai.tegmentum.jena.webfunctions.rewrite.AliasRewriteState;
import ai.tegmentum.jena.webfunctions.rewrite.ConversionRegistry;
import ai.tegmentum.jena.webfunctions.rewrite.InvokeRegistry;
import ai.tegmentum.jena.webfunctions.rewrite.RewritePipeline;
import ai.tegmentum.jena.webfunctions.rewrite.ShapeRegistry;
import ai.tegmentum.jena.webfunctions.rewrite.WebFunctionQueryEngine;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.graph.Node;
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
 *   [--conversion-config conversion.json] [--partial-config partial.json]
 * }</pre>
 *
 * <p>Exit code 0 on success; non-zero with an error line on stderr on
 * failure. When no config files are passed the pipeline is empty and the
 * runner behaves as a plain SPARQL passthrough.
 */
public final class ConformanceMain {

    private ConformanceMain() {}

    public static void main(final String[] args) {
        try {
            run(args, System.out);
        } catch (Throwable t) {
            System.err.println("conformance-main: " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    /**
     * Test-friendly entry point. Doesn't call {@link System#exit(int)};
     * exceptions propagate.
     */
    public static void run(final String[] args, final PrintStream out) throws Exception {
        final Map<String, String> parsed = parseArgs(args);
        final Path dataPath = requirePathArg(parsed, "--data");
        final Path queryPath = requirePathArg(parsed, "--query");
        final Path aliasCfg = optionalPathArg(parsed, "--alias-config");
        final Path shapeCfg = optionalPathArg(parsed, "--shape-config");
        final Path conversionCfg = optionalPathArg(parsed, "--conversion-config");
        final Path partialCfg = optionalPathArg(parsed, "--partial-config");

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
                invokeRegistry, conversionRegistry, aliasMap, shapeRegistry, wfFetchUrl);
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
            final ResultSet rs = qe.execSelect();
            // ALIAS_STATE_SYMBOL is stashed on the engine's Context during
            // modifyOp — which is the same Context instance backing
            // qe.getContext(). Pull it out here for the output rewrite.
            final Object stateObj = qe.getContext().get(
                    WebFunctionQueryEngine.ALIAS_STATE_SYMBOL);
            final AliasRewriteState state = stateObj instanceof AliasRewriteState s
                    ? s : AliasRewriteState.inactive();
            final ResultSet wrapped = wrapWithAliasRewrite(rs, state);
            ResultSetFormatter.outputAsJSON(out, wrapped);
            out.flush();
        }
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
