package ai.tegmentum.jena.webfunctions.rewrite;

import ai.tegmentum.jena.webfunctions.rewrite.FederationRegistry.Column;
import ai.tegmentum.jena.webfunctions.rewrite.FederationRegistry.FederationSource;
import ai.tegmentum.jena.webfunctions.rewrite.FederationRegistry.RelationalConfig;
import ai.tegmentum.jena.webfunctions.rewrite.FederationRegistry.SourceType;

import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.TransformCopy;
import org.apache.jena.sparql.algebra.Transformer;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpService;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * URL-sugar rewrite: fold
 * {@code SERVICE <wf-relational:<name>>} clauses (produced upstream by
 * {@link WfFederationRewrite} for FederationSources of type
 * {@link SourceType#WF_RELATIONAL}) into the substrate's ordinary
 * {@code SERVICE <wf:call>} envelope calling {@code wf_fetch.wasm} with
 * a Postgres-backed shape descriptor.
 *
 * <p>Design memo: {@code wf-conformance/docs/design/wf-relational.md}
 * &sect;04 (a {@code wf-relational} source's schema DDL is the shape
 * descriptor; the federation pass emits {@code wf-relational:<name>}; a
 * downstream fold pass turns that into a wf_fetch.wasm dispatch whose
 * descriptor's {@code sink_kind = "postgres"} steers the guest to
 * Postgres-SQL).
 *
 * <h3>Wire shape</h3>
 * <pre>
 * Before (produced upstream by {@link WfFederationRewrite}):
 * SERVICE &lt;wf-relational:customers&gt; {
 *   ?c :name ?name ; :tier ?tier
 * }
 *
 * After (same envelope {@link ShapeRewrite} / {@link WfFetchRewrite} emit):
 * SERVICE &lt;wf:call&gt; {
 *   _:c wf:wasm &lt;file:///.../wf_fetch.wasm&gt; ;
 *       wf:arg  "&lt;descriptor-json&gt;" .
 *   _:o wf:id    ?c ;
 *       wf:name  ?name ;
 *       wf:tier  ?tier .
 * }
 * </pre>
 *
 * <h3>Shape descriptor</h3>
 * The descriptor baked into {@code wf:arg} mirrors the shape wf_fetch
 * consumes for SQLite-backed shapes, with three additions:
 * <ul>
 *   <li>{@code sink_kind = "postgres"} &mdash; steers the guest to
 *       Postgres-SQL placeholder conventions ({@code ?} &rarr;
 *       {@code $N}) and the Postgres sink scheme in {@code sink::open}.</li>
 *   <li>{@code sink = "<postgres-url>#<table>"} &mdash; the connection
 *       URL from the FederationSource entry's {@code endpoint},
 *       fragmented with the descriptor's table name (mirrors sqlite sink
 *       URL format).</li>
 *   <li>{@code include_graph = false} &mdash; Postgres tables have no
 *       {@code _graph} column; wf_fetch omits it from its SELECT
 *       projection when this flag is false.</li>
 * </ul>
 *
 * <h3>Provenance sidecar ({@code _shape_version})</h3>
 * When the descriptor has {@code emit_provenance = true} and
 * {@code schema_version} set, the shape descriptor carries
 * {@code schema_version} through so the guest's wf_fetch can attach
 * {@code ?_shape_version} bindings to each row (memo &sect;07). This
 * pass never mints the sidecar variable itself &mdash; the guest is
 * responsible for emitting the extra column when the caller asks for it.
 *
 * <h3>Position in the pipeline</h3>
 * Same slot as {@link WfFetchRewrite}: after {@link WfFederationRewrite}
 * (which emits the {@code wf-relational:} URLs this pass consumes) and
 * before {@link ShapeRewrite}. Empty federation registry or empty
 * wfFetchUrl &rarr; short-circuit; unknown name &rarr; leave the
 * SERVICE alone; wrong source type &rarr; leave alone; source missing
 * a {@code relational} block &rarr; leave alone.
 *
 * <h3>v0.3 unification</h3>
 * Prior to v0.3 the shape descriptor lived in a sidecar
 * {@code WfRelationalRegistry} that re-parsed the same JSON file
 * {@link FederationRegistry} consumed. v0.3 folds the descriptor into
 * {@link FederationSource#relationalConfig()}, and this pass reads it
 * from there &mdash; one lookup path, one source of truth. See
 * {@link FederationRegistry} for the field.
 *
 * <p>Java sibling of {@code oxigraph-wf/src/wf_relational_rewrite.rs}.
 */
public final class WfRelationalRewrite {

    /** The URL scheme this pass recognises at the SERVICE position. */
    public static final String WF_RELATIONAL_SCHEME = "wf-relational:";

    private static final String WF_NS = "http://tegmentum.ai/ns/webfunction/";
    private static final String WF_CALL_IRI = WF_NS + "call";
    private static final String WF_WASM_IRI = WF_NS + "wasm";
    private static final String WF_ARG_IRI  = WF_NS + "arg";
    private static final String RDF_TYPE =
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    private WfRelationalRewrite() {}

    /**
     * @return rewritten Op &mdash; a copy with qualifying
     *     {@code SERVICE <wf-relational:...>} clauses replaced by
     *     {@code SERVICE <wf:call>} envelopes; identity when the
     *     federation registry is empty or the fetch URL is null.
     */
    public static Op rewrite(final Op op,
                             final FederationRegistry federationRegistry,
                             final String wfFetchUrl) {
        if (op == null) return null;
        if (federationRegistry == null || federationRegistry.isEmpty()) return op;
        if (wfFetchUrl == null || wfFetchUrl.isEmpty()) return op;
        return Transformer.transform(
                new RelationalTransform(federationRegistry, wfFetchUrl), op);
    }

    // ---------------------------------------------------------------------
    // Transform
    // ---------------------------------------------------------------------

    private static final class RelationalTransform extends TransformCopy {
        private final FederationRegistry federationRegistry;
        private final String wfFetchUrl;

        RelationalTransform(final FederationRegistry federationRegistry,
                            final String wfFetchUrl) {
            this.federationRegistry = federationRegistry;
            this.wfFetchUrl = wfFetchUrl;
        }

        @Override
        public Op transform(final OpService opService, final Op subOp) {
            final Node svc = opService.getService();
            if (svc == null || !svc.isURI()) {
                return super.transform(opService, subOp);
            }
            final String uri = svc.getURI();
            if (!uri.startsWith(WF_RELATIONAL_SCHEME)) {
                return super.transform(opService, subOp);
            }
            final String name = uri.substring(WF_RELATIONAL_SCHEME.length());
            if (name.isEmpty()) {
                return super.transform(opService, subOp);
            }
            final FederationSource entry = federationRegistry.byName(name);
            if (entry == null) {
                // Unknown source name — leave the SERVICE alone.
                return super.transform(opService, subOp);
            }
            if (entry.sourceType() != SourceType.WF_RELATIONAL) {
                // Wrong source type — refuse to fold even if a
                // `relational` block is (mis)configured on it.
                return super.transform(opService, subOp);
            }
            if (entry.relationalConfig().isEmpty()) {
                // wf-relational source with no descriptor block — same
                // "descriptor missing, skip" semantics the old sidecar
                // registry provided when its per-name lookup missed.
                return super.transform(opService, subOp);
            }
            final RelationalConfig cfg = entry.relationalConfig().get();

            // Extract BGP triples from the SERVICE body.
            final BasicPattern bgp = collectBgp(subOp);
            if (bgp == null || bgp.isEmpty()) {
                return super.transform(opService, subOp);
            }
            final Var subjectVar = singleSubjectVariable(bgp);
            if (subjectVar == null) {
                return super.transform(opService, subOp);
            }

            final Map<String, String> byPred = cfg.columnsByPredicate();
            final List<Map.Entry<String, Var>> columns =
                    new ArrayList<>(bgp.size());
            for (Triple t : bgp) {
                final Node p = t.getPredicate();
                if (!p.isURI()) {
                    return super.transform(opService, subOp);
                }
                final String predIri = p.getURI();
                // rdf:type triples are structural — the anchor class is
                // baked into the descriptor already. Skip.
                if (RDF_TYPE.equals(predIri)) {
                    continue;
                }
                final Node obj = t.getObject();
                if (!obj.isVariable()) {
                    return super.transform(opService, subOp);
                }
                final String col = byPred.get(predIri);
                if (col == null) {
                    return super.transform(opService, subOp);
                }
                columns.add(new AbstractMap.SimpleImmutableEntry<>(
                        col, Var.alloc(obj.getName())));
            }
            if (columns.isEmpty()) {
                return super.transform(opService, subOp);
            }
            return buildWfCallService(subjectVar, entry, cfg, columns);
        }

        /**
         * The federation-emitted body is always an {@link OpBGP}; be
         * tolerant to that concrete class. Returns {@code null} if the
         * sub-op isn't a plain BGP shape.
         */
        private static BasicPattern collectBgp(final Op op) {
            if (op instanceof OpBGP bgp) {
                return bgp.getPattern();
            }
            return null;
        }

        /**
         * All triples must share the same subject variable. Same rule as
         * {@link WfFetchRewrite}'s private helper.
         */
        private static Var singleSubjectVariable(final BasicPattern pattern) {
            Var chosen = null;
            for (Triple t : pattern) {
                final Node s = t.getSubject();
                if (!s.isVariable()) {
                    return null;
                }
                final Var v = Var.alloc(s.getName());
                if (chosen == null) {
                    chosen = v;
                } else if (!chosen.equals(v)) {
                    return null;
                }
            }
            return chosen;
        }

        /**
         * Construct the same SERVICE-envelope Op that {@link ShapeRewrite}
         * and {@link WfFetchRewrite} emit, with the Postgres-flavoured
         * descriptor JSON baked into the {@code wf:arg} literal.
         */
        private Op buildWfCallService(final Var subjectVar,
                                      final FederationSource entry,
                                      final RelationalConfig cfg,
                                      final List<Map.Entry<String, Var>> columns) {
            final Node cnode = NodeFactory.createBlankNode();
            final Node onode = NodeFactory.createBlankNode();

            final String descriptorJson = buildDescriptorJson(entry, cfg);

            final BasicPattern bp = new BasicPattern();
            // Config side (cnode): the wasm URL and descriptor JSON literal.
            bp.add(Triple.create(cnode,
                    NodeFactory.createURI(WF_WASM_IRI),
                    NodeFactory.createURI(wfFetchUrl)));
            bp.add(Triple.create(cnode,
                    NodeFactory.createURI(WF_ARG_IRI),
                    NodeFactory.createLiteralString(descriptorJson)));

            // Output side (onode).
            bp.add(Triple.create(onode,
                    NodeFactory.createURI(WF_NS + cfg.subjectColumn()),
                    subjectVar));
            for (Map.Entry<String, Var> col : columns) {
                bp.add(Triple.create(onode,
                        NodeFactory.createURI(WF_NS + col.getKey()),
                        col.getValue()));
            }

            return new OpService(NodeFactory.createURI(WF_CALL_IRI),
                    new OpBGP(bp), false);
        }
    }

    // ---------------------------------------------------------------------
    // Descriptor JSON
    // ---------------------------------------------------------------------

    /**
     * Assemble the wf_fetch descriptor JSON for a Postgres-backed shape.
     * Adds the three fields wf_fetch needs beyond the SQLite-descriptor
     * shape ({@code sink_kind}, {@code sink}, {@code include_graph}),
     * plus carries {@code schema_version} through so the guest can
     * honour the {@code ?_shape_version} provenance sidecar when the
     * caller asks for it (memo &sect;07).
     *
     * <p>Package-private for the WfRelationalRewriteTest fixture &mdash;
     * saves the tests from having to inline the same JSON shape.
     */
    static String buildDescriptorJson(final FederationSource entry,
                                      final RelationalConfig cfg) {
        // `sink` = "<endpoint>#<table>" mirrors the sqlite sink URL format
        // (`sqlite:///path/to.db#tablename`). The Postgres sink
        // implementation strips the fragment when connecting; the fragment
        // stays as human-readable metadata + a way for callers to see
        // which table the guest will query.
        final String sinkUrl = entry.endpoint() + "#" + cfg.table();

        final JsonArray columnsJson = new JsonArray();
        for (Column c : cfg.columns()) {
            final JsonObject obj = new JsonObject();
            obj.put("name", c.name());
            obj.put("role", c.role());
            if (c.xsdType() != null) {
                obj.put("type", c.xsdType());
            }
            if (c.predicate() != null) {
                obj.put("predicate", c.predicate());
            }
            columnsJson.add(obj);
        }

        final JsonObject anchorJson = new JsonObject();
        cfg.anchor().anchorClass().ifPresent(cls -> anchorJson.put("class", cls));

        final JsonObject root = new JsonObject();
        root.put("name", entry.name());
        root.put("shape", entry.name());
        root.put("sink", sinkUrl);
        root.put("sink_kind", cfg.sinkKind());
        root.put("include_graph", false);
        root.put("table", cfg.table());
        root.put("subject_column", cfg.subjectColumn());
        root.put("anchor", anchorJson);
        root.put("columns", columnsJson);
        root.put("emit_provenance", cfg.emitProvenance());
        cfg.iriTemplate().ifPresent(t -> root.put("iri_template", t));
        cfg.schemaVersion().ifPresent(v -> root.put("schema_version", v));
        return root.toString();
    }
}
