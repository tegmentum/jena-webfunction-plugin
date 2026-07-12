package ai.tegmentum.jena.webfunctions.conformance;

import ai.tegmentum.jena.webfunctions.rewrite.WebFunctionQueryEngine;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.query.ARQ;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the conformance runner in-process. Two cases:
 *
 * <ul>
 *   <li>Passthrough — no configs, plain SELECT over a small graph. Runner
 *       must serialize the results in SPARQL Results JSON with the two
 *       expected friends.</li>
 *   <li>Alias rewrite — the same query mentions an aliased subject IRI.
 *       The result set must come back with the alias on the output path,
 *       not the canonical form, proving that {@code AliasRewriteState}
 *       was pulled off the ARQ Context and applied to the output rows.</li>
 * </ul>
 */
public class ConformanceMainTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Before
    public void resetContext() {
        ARQ.getContext().remove(WebFunctionQueryEngine.PIPELINE_SYMBOL);
        ARQ.getContext().remove(WebFunctionQueryEngine.ALIAS_STATE_SYMBOL);
    }

    @After
    public void clearContext() {
        ARQ.getContext().remove(WebFunctionQueryEngine.PIPELINE_SYMBOL);
        ARQ.getContext().remove(WebFunctionQueryEngine.ALIAS_STATE_SYMBOL);
    }

    @Test
    public void passthrough_selectReturnsExpectedRows() throws Exception {
        // A tiny graph: alice knows bob and carol.
        final Path dataFile = tmp.newFile("data.ttl").toPath();
        Files.writeString(dataFile,
                "@prefix foaf: <http://xmlns.com/foaf/0.1/> .\n"
              + "@prefix ex: <http://example.com/> .\n"
              + "ex:alice foaf:knows ex:bob , ex:carol .\n");

        final Path queryFile = tmp.newFile("query.sparql").toPath();
        Files.writeString(queryFile,
                "PREFIX foaf: <http://xmlns.com/foaf/0.1/>\n"
              + "PREFIX ex: <http://example.com/>\n"
              + "SELECT ?friend WHERE { ex:alice foaf:knows ?friend } ORDER BY ?friend\n");

        final String stdout = runToString(
                "--data", dataFile.toString(),
                "--query", queryFile.toString());

        final Set<String> friends = extractUriBindings(stdout, "friend");
        assertThat(friends).containsExactlyInAnyOrder(
                "http://example.com/bob", "http://example.com/carol");
    }

    @Test
    public void aliasRewrite_selectReturnsAliasOnOutputPath() throws Exception {
        // Data stores facts under the canonical IRI; the caller queries
        // and expects results under the alias IRI they mentioned.
        final String canonical = "http://example.com/.well-known/genid/1";
        final String alias = "http://example.com/alice";

        final Path dataFile = tmp.newFile("data.ttl").toPath();
        Files.writeString(dataFile,
                "@prefix foaf: <http://xmlns.com/foaf/0.1/> .\n"
              + "<" + canonical + "> foaf:knows <http://example.com/bob> .\n");

        // The query asks about the alias — after the alias rewrite pass
        // the BGP references the canonical, but the output rewrite maps
        // any canonical subject on the way out back to the alias.
        final Path queryFile = tmp.newFile("query.sparql").toPath();
        Files.writeString(queryFile,
                "PREFIX foaf: <http://xmlns.com/foaf/0.1/>\n"
              + "SELECT ?s ?friend WHERE { ?s foaf:knows ?friend . FILTER (?s = <"
              + alias + ">) }\n");

        final Path aliasCfg = tmp.newFile("alias.json").toPath();
        Files.writeString(aliasCfg,
                "{\"aliases\": {\"" + alias + "\": \"" + canonical + "\"}}\n");

        final String stdout = runToString(
                "--data", dataFile.toString(),
                "--query", queryFile.toString(),
                "--alias-config", aliasCfg.toString());

        final Set<String> subjects = extractUriBindings(stdout, "s");
        assertThat(subjects).containsExactly(alias);

        final Set<String> friends = extractUriBindings(stdout, "friend");
        assertThat(friends).containsExactly("http://example.com/bob");
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static String runToString(final String... args) throws Exception {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8)) {
            ConformanceMain.run(args, ps);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    /**
     * Parse the SPARQL Results JSON on stdout and pull out every URI
     * binding for {@code varName}. Ignores literal / blank-node values
     * (the tests only pin IRIs).
     */
    private static Set<String> extractUriBindings(final String json, final String varName) {
        final JsonObject root = JSON.parse(json);
        assertThat(root.hasKey("results")).as("has results object").isTrue();
        final JsonObject results = root.get("results").getAsObject();
        assertThat(results.hasKey("bindings")).as("has bindings array").isTrue();
        final JsonArray bindings = results.get("bindings").getAsArray();
        final Set<String> out = new HashSet<>();
        final List<String> ordered = new ArrayList<>();
        for (JsonValue jv : bindings) {
            final JsonObject row = jv.getAsObject();
            if (!row.hasKey(varName)) continue;
            final JsonObject value = row.get(varName).getAsObject();
            if (!"uri".equals(value.get("type").getAsString().value())) continue;
            final String v = value.get("value").getAsString().value();
            out.add(v);
            ordered.add(v);
        }
        // Preserve insertion order in a defensive copy for assertj's
        // containsExactly*. We return a Set for containsExactlyInAnyOrder.
        return new java.util.LinkedHashSet<>(ordered);
    }
}
