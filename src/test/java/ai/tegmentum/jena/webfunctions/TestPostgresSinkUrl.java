package ai.tegmentum.jena.webfunctions;

import org.junit.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Deterministic tests for the {@code postgres://} URL parser inside
 * {@link PostgresSink}. Kept separate from the end-to-end sink test
 * (which needs a live Postgres and lives in wf-conformance's
 * relational_basic case) so this file always runs green in CI.
 */
public class TestPostgresSinkUrl {

    @Test
    public void parsesUserinfoAndAuthority() throws SQLException {
        PostgresSink.ParsedPostgresUrl p = PostgresSink.ParsedPostgresUrl.parse(
                "postgres://wf:wf@127.0.0.1:5432/wf");
        assertThat(p.user).isEqualTo("wf");
        assertThat(p.password).isEqualTo("wf");
        assertThat(p.jdbcUrl).isEqualTo("jdbc:postgresql://127.0.0.1:5432/wf");
        assertThat(p.queryParams).isEmpty();
    }

    @Test
    public void acceptsPostgresqlSchemeAlias() throws SQLException {
        PostgresSink.ParsedPostgresUrl p = PostgresSink.ParsedPostgresUrl.parse(
                "postgresql://wf:wf@127.0.0.1:5432/wf");
        assertThat(p.jdbcUrl).isEqualTo("jdbc:postgresql://127.0.0.1:5432/wf");
    }

    @Test
    public void liftsQueryParamsIntoConnectionProperties() throws SQLException {
        PostgresSink.ParsedPostgresUrl p = PostgresSink.ParsedPostgresUrl.parse(
                "postgres://wf@127.0.0.1:5432/wf?sslmode=require&connect_timeout=5");
        assertThat(p.user).isEqualTo("wf");
        assertThat(p.password).isNull();
        assertThat(p.queryParams)
                .containsEntry("sslmode", "require")
                .containsEntry("connect_timeout", "5");
    }

    @Test
    public void urlDecodesUserinfo() throws SQLException {
        // Password with a URL-escaped '@' — the parser must decode it,
        // otherwise the JDBC connect call would receive the raw %40.
        PostgresSink.ParsedPostgresUrl p = PostgresSink.ParsedPostgresUrl.parse(
                "postgres://u:p%40ss@host:5432/db");
        assertThat(p.user).isEqualTo("u");
        assertThat(p.password).isEqualTo("p@ss");
    }

    @Test
    public void rejectsWrongScheme() {
        Throwable t = catchThrowable(() ->
                PostgresSink.ParsedPostgresUrl.parse("mongodb://host/db"));
        assertThat(t).isInstanceOf(SQLException.class)
                .hasMessageContaining("postgres://");
    }

    @Test
    public void sinkOpenDispatchesPostgresScheme() {
        // The dispatch table in SinkOpen should recognise both scheme
        // aliases. We can't reach the driver here (no live Postgres),
        // so we accept either a connect-time SQLException or the parser
        // returning cleanly — what we're actually testing is that the
        // scheme is NOT rejected as "not supported".
        Throwable t = catchThrowable(() -> SinkOpen.open("postgres://x:y@127.0.0.1:1/db"));
        // If a driver-connect failure surfaces, it's a SQLException.
        // If the URL parses and connect somehow succeeds (impossible on
        // port 1), t is null. If SinkOpen rejects the scheme, t would
        // be IllegalArgumentException with "not supported" — this test
        // asserts we do NOT see that.
        if (t != null) {
            assertThat(t).isNotInstanceOf(IllegalArgumentException.class);
        }
    }
}
