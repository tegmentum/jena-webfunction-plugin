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
 * Integration test for the {@code --document-config} flag on the
 * conformance runner. Confirms that the runner accepts the flag, loads
 * the JSON via {@link ai.tegmentum.jena.webfunctions.rewrite.DocumentRegistry},
 * and reports the parsed entry count on stderr.
 *
 * <p>Does not drive a Manticore + Sirix roundtrip end-to-end &mdash;
 * that lives in the wf-conformance parity suite. This test's job is
 * just to prove the runner surface is wired.
 *
 * <p>The runner is invoked out-of-process so the test can observe
 * stderr independently of stdout, matching
 * {@link ConformanceMainFulltextTest}.
 */
public class ConformanceMainDocumentTest {

    @Test
    public void runnerLoadsDocumentConfigAndReportsCount() throws Exception {
        final Path tmp = Files.createTempDirectory("wf-conformance-document");
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

            // Two-entry fixture: one managed, one federated. Structurally
            // aligned with the design-memo §07 example.
            final Path documentConfig = tmp.resolve("document.json");
            Files.writeString(documentConfig, """
                    {
                      "documents": [
                        {
                          "name": "manuals",
                          "mode": "managed",
                          "guest_url": "file:///opt/wf_document.wasm",
                          "search_backend": "http://localhost:9308",
                          "storage_backend": "http://localhost:8080",
                          "search_index": "manuals",
                          "sirix_database": "docs",
                          "sirix_resource": "manuals",
                          "sweep_interval_secs": 300,
                          "revision_retention": "latest"
                        },
                        {
                          "name": "external",
                          "mode": "federated",
                          "guest_url": "file:///opt/wf_document.wasm",
                          "search_backend": "http://search.example/",
                          "storage_backend": "http://storage.example/",
                          "search_index": "kb",
                          "sirix_database": "kb",
                          "sirix_resource": "kb"
                        }
                      ]
                    }
                    """, StandardCharsets.UTF_8);

            final ProcessResult r = runMain(
                    "--data", data.toString(),
                    "--query", query.toString(),
                    "--document-config", documentConfig.toString());
            assertThat(r.exitCode)
                    .as("stderr:\n" + r.stderr + "\nstdout:\n" + r.stdout)
                    .isZero();

            // The runner emits a stderr diagnostic that names the config
            // path and reports the parsed count.
            assertThat(r.stderr)
                    .as("stderr should confirm the document registry loaded")
                    .contains("loaded 2 document")
                    .contains(documentConfig.toString());

            // Existing SPARQL query still executes cleanly — the flag
            // must not disturb pre-document output.
            assertThat(r.stdout).contains("hello world");
        } finally {
            deleteRecursively(tmp);
        }
    }

    /**
     * A malformed document config (managed entry without
     * {@code sweep_interval_secs}) exits with the config-error path.
     * Confirms the parser's validation surface reaches the runner.
     */
    @Test
    public void runnerRejectsInvalidDocumentConfig() throws Exception {
        final Path tmp = Files.createTempDirectory("wf-conformance-document-bad");
        try {
            final Path data = tmp.resolve("data.ttl");
            Files.writeString(data, "", StandardCharsets.UTF_8);
            final Path query = tmp.resolve("query.sparql");
            Files.writeString(query, "SELECT * WHERE { ?s ?p ?o }", StandardCharsets.UTF_8);

            final Path documentConfig = tmp.resolve("bad.json");
            Files.writeString(documentConfig, """
                    {
                      "documents": [{
                        "name": "bad",
                        "mode": "managed",
                        "guest_url": "file:///x.wasm",
                        "search_backend": "http://s/",
                        "storage_backend": "http://t/",
                        "search_index": "bad",
                        "sirix_database": "d",
                        "sirix_resource": "bad",
                        "revision_retention": "latest"
                      }]
                    }
                    """, StandardCharsets.UTF_8);

            final ProcessResult r = runMain(
                    "--data", data.toString(),
                    "--query", query.toString(),
                    "--document-config", documentConfig.toString());
            assertThat(r.exitCode).isEqualTo(2);
            assertThat(r.stderr).contains("document config error");
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
