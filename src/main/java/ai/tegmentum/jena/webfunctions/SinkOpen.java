package ai.tegmentum.jena.webfunctions;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Dispatch entry point for {@code sink-open}. Parses the URL, selects a
 * backend by scheme, and delegates to that backend's constructor.
 *
 * <p>Recognised schemes:
 * <ul>
 *   <li>{@code sqlite://} — v0.5 SQLite backend via {@code sqlite-jdbc}.</li>
 *   <li>{@code postgres://} / {@code postgresql://} — wf_relational v0.1
 *       Postgres backend via the PostgreSQL JDBC driver.</li>
 * </ul>
 * Future backends (duckdb, sirix) slot in as new {@code case} arms.
 */
final class SinkOpen {

    private SinkOpen() {}

    static Sink open(final String rawUrl) throws Exception {
        // Extract the scheme by hand — java.net.URI's parser rejects some
        // valid postgres:// URLs (e.g. userinfo containing '@'-escaped
        // characters). Everything up to ':' is the scheme; the backend
        // opens the raw URL itself.
        final int colon = rawUrl.indexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("sink url `" + rawUrl + "` has no scheme");
        }
        final String scheme = rawUrl.substring(0, colon).toLowerCase(Locale.ROOT);
        switch (scheme) {
            case "sqlite": {
                // SqliteSink expects a URI to inspect authority/path; keep
                // that path unchanged so the sqlite-jdbc plumbing runs
                // exactly as before.
                final URI uri;
                try {
                    uri = new URI(rawUrl);
                } catch (URISyntaxException e) {
                    throw new IllegalArgumentException(
                        "sink url `" + rawUrl + "` did not parse: " + e.getMessage(), e);
                }
                return SqliteSink.open(uri);
            }
            case "postgres":
            case "postgresql":
                return PostgresSink.open(rawUrl);
            default:
                throw new IllegalArgumentException(
                    "sink scheme `" + scheme + "` not supported (v0.5 ships sqlite, postgres)");
        }
    }
}
