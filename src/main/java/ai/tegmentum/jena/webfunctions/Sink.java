package ai.tegmentum.jena.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;

import java.util.List;

/**
 * Sink backend for the v0.5 {@code sink-*} host imports. A sink is an
 * out-of-graph destination guests write to during materialization and read
 * from during federated query. Handle-based: the guest opens a URL once,
 * executes many statements against the returned handle, and closes it.
 *
 * <p>v0.5 ships SQLite only. DuckDB and Postgres slot in behind the same
 * trait; SirixDB is a bigger lift because its query language isn't SQL
 * (Brackit/XQuery) but the trait shape absorbs that as long as guests
 * parameterize their statements per backend.
 */
public interface Sink extends AutoCloseable {

    /**
     * Execute an opaque query in the backend's native language. Returns
     * a WIT {@code binding-sets} record — empty for DDL/DML, populated for
     * SELECT.
     */
    ComponentVal execute(String query, List<ComponentVal> params) throws Exception;

    @Override
    void close() throws Exception;
}
