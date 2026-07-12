package ai.tegmentum.jena.webfunctions.conformance;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the {@code --fulltext-config} flag on the
 * conformance runner. Confirms that the runner accepts the flag, loads
 * the JSON via {@link ai.tegmentum.jena.webfunctions.rewrite.FulltextRegistry},
 * and reports the parsed entry count on stderr.
 *
 * <p>Does not drive a Manticore query end-to-end &mdash; that lives in
 * the wf-conformance parity suite. This test's job is just to prove
 * the runner surface is wired.
 *
 * <p>The runner is invoked out-of-process so the test can observe
 * stderr independently of stdout, matching the RDF4J sibling
 * ({@code ConformanceMainFulltextTest}) verbatim.
 */
public class ConformanceMainFulltextTest {

    @Test
    public void runnerLoadsFulltextConfigAndReportsCount() throws Exception {
        final Path tmp = Files.createTempDirectory("wf-conformance-fulltext");
        try {
            final Path data = tmp.resolve("data.ttl");
            Files.writeString(data,
                    "@prefix ex: <http://example/> .\n"
                            + "ex:s1 ex:label \"hello world\" .\n",
                    StandardCharsets.UTF_8);

            final Path query = tmp.resolve("query.sparql");
            Files.writeString(query,
                    "SELECT ?s ?l WHERE { ?s <http://example/label> ?l }",
                    StandardCharsets.UTF_8);

            // Two-entry fixture: one literal-index, one document-corpus.
            // Structurally identical to the design-memo §06 example.
            final Path fulltextConfig = tmp.resolve("fulltext.json");
            Files.writeString(fulltextConfig, """
                    {
                      "indexes": [
                        {
                          "name": "labels",
                          "mode": "literal-index",
                          "predicates": ["http://example/label"],
                          "backend_url": "file:///opt/wf_fulltext.wasm",
                          "opts": { "index": "labels" },
                          "languages": ["en"],
                          "sweep_interval_secs": 60
                        },
                        {
                          "name": "manuals",
                          "mode": "document-corpus",
                          "backend_url": "file:///opt/wf_fulltext.wasm",
                          "opts": { "index": "manuals" }
                        }
                      ]
                    }
                    """, StandardCharsets.UTF_8);

            final ProcessResult r = runMain(
                    "--data", data.toString(),
                    "--query", query.toString(),
                    "--fulltext-config", fulltextConfig.toString());
            assertThat(r.exitCode)
                    .as("stderr:\n" + r.stderr + "\nstdout:\n" + r.stdout)
                    .isZero();

            // The runner emits a stderr diagnostic that names the config
            // path and reports the parsed count.
            assertThat(r.stderr)
                    .as("stderr should confirm the fulltext registry loaded")
                    .contains("loaded 2 fulltext index")
                    .contains(fulltextConfig.toString());

            // Existing SPARQL query still executes cleanly — the flag
            // must not disturb pre-fulltext output.
            assertThat(r.stdout).contains("hello world");
        } finally {
            deleteRecursively(tmp);
        }
    }

    /**
     * A malformed fulltext config (literal-index with no predicates)
     * exits with a distinct config-error path. Confirms the parser's
     * validation surface reaches the runner.
     */
    @Test
    public void runnerRejectsInvalidFulltextConfig() throws Exception {
        final Path tmp = Files.createTempDirectory("wf-conformance-fulltext-bad");
        try {
            final Path data = tmp.resolve("data.ttl");
            Files.writeString(data, "", StandardCharsets.UTF_8);
            final Path query = tmp.resolve("query.sparql");
            Files.writeString(query, "SELECT * WHERE { ?s ?p ?o }", StandardCharsets.UTF_8);

            final Path fulltextConfig = tmp.resolve("bad.json");
            Files.writeString(fulltextConfig, """
                    {
                      "indexes": [{
                        "name": "bad",
                        "mode": "literal-index",
                        "backend_url": "file:///x.wasm",
                        "opts": {}
                      }]
                    }
                    """, StandardCharsets.UTF_8);

            final ProcessResult r = runMain(
                    "--data", data.toString(),
                    "--query", query.toString(),
                    "--fulltext-config", fulltextConfig.toString());
            assertThat(r.exitCode).isEqualTo(2);
            assertThat(r.stderr).contains("fulltext config error");
        } finally {
            deleteRecursively(tmp);
        }
    }

    // ---- shell-out helpers -----------------------------------------------

    private static final class ProcessResult {
        int exitCode;
        String stdout;
        String stderr;
    }

    private ProcessResult runMain(final String... args) throws IOException, InterruptedException {
        final String javaHome = System.getProperty("java.home");
        final String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
        final String cp = System.getProperty("java.class.path");

        final List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-cp");
        cmd.add(cp);
        cmd.add(ConformanceMain.class.getName());
        for (String a : args) cmd.add(a);

        final ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        final Process p = pb.start();
        final byte[] out = p.getInputStream().readAllBytes();
        final byte[] err = p.getErrorStream().readAllBytes();
        final ProcessResult r = new ProcessResult();
        r.exitCode = p.waitFor();
        r.stdout = new String(out, StandardCharsets.UTF_8);
        r.stderr = new String(err, StandardCharsets.UTF_8);
        return r;
    }

    private static void deleteRecursively(final Path root) {
        try {
            Files.walk(root)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {
        }
    }
}
