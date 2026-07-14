package ai.tegmentum.jena.webfunctions;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Dispatch entry point for {@code sink-open}. Parses the URL, selects a
 * backend by scheme, and delegates to that backend's constructor.
 *
 * <p>v0.5 recognises {@code sqlite://} only. Future backends (duckdb,
 * postgres, sirix) slot in as new {@code case} arms.
 */
final class SinkOpen {

    private SinkOpen() {}

    static Sink open(final String rawUrl) throws Exception {
        final URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(
                "sink url `" + rawUrl + "` did not parse: " + e.getMessage(), e);
        }
        final String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("sink url `" + rawUrl + "` has no scheme");
        }
        switch (scheme) {
            case "sqlite":
                return SqliteSink.open(uri);
            default:
                throw new IllegalArgumentException(
                    "sink scheme `" + scheme + "` not supported (v0.5 ships sqlite only)");
        }
    }
}
