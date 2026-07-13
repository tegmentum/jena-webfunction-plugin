package ai.tegmentum.jena.webfunctions.rewrite;

import ai.tegmentum.jena.webfunctions.rewrite.FederationRegistry.FederationSource;
import ai.tegmentum.jena.webfunctions.rewrite.FederationRegistry.SourceType;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVars;
import org.apache.jena.sparql.algebra.TransformCopy;
import org.apache.jena.sparql.algebra.Transformer;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpFilter;
import org.apache.jena.sparql.algebra.op.OpJoin;
import org.apache.jena.sparql.algebra.op.OpService;
import org.apache.jena.sparql.algebra.op.OpUnion;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static-mode federation rewrite: assign BGP triple patterns to
 * registered federated sources, group same-source patterns into single
 * {@code SERVICE} calls, push filters into the source that binds their
 * free variables.
 *
 * <p>Design memo: {@code wf-conformance/docs/design/wf-federation.md}
 * &sect;03 (registry), &sect;04 (rewrite semantics), &sect;07 (cost
 * model, uniform at v0.1), &sect;11 (ship order &mdash; this is step 1+2+3).
 *
 * <h3>Rewrite algorithm (memo &sect;04)</h3>
 * <ol>
 *   <li><b>Decompose.</b> Each {@link OpBGP} is split into its
 *       individual triple patterns. Explicit {@link OpService} clauses
 *       are left untouched &mdash; the caller opted into a specific
 *       source and we respect that.</li>
 *   <li><b>Assign.</b> A triple with a concrete-IRI predicate looks up
 *       the source(s) that declared it. Unambiguous (one source) gets
 *       assigned to that source; multi-source produces a
 *       {@link OpUnion} over per-source {@link OpService} clauses;
 *       unregistered predicates stay in a leftover local BGP.</li>
 *   <li><b>Group.</b> Same-source triples that share a subject or
 *       object variable get combined into one {@link OpService}. A join
 *       between two same-source patterns happens inside the source, not
 *       client-side &mdash; the biggest single win over naive
 *       dispatch.</li>
 *   <li><b>Splice.</b> A {@link OpFilter} whose free variables are all
 *       bound within one {@link OpService} inside its transformed sub-op
 *       gets pushed into that service's body; filters with cross-source
 *       free variables stay at the outer level.</li>
 * </ol>
 *
 * <h3>SERVICE URL emission (memo &sect;06)</h3>
 * <ul>
 *   <li>{@link SourceType#WF_SEARCH} / {@link SourceType#WF_FETCH} /
 *       {@link SourceType#WF_DOCUMENT} &mdash; emit the substrate
 *       URL-sugar form ({@code wf-search:<name>}, etc.). The
 *       {@link WfSearchRewrite} pass runs after this one and expands
 *       the sugar into a {@code wf-invoke:} dispatch when the body
 *       supplies a query literal.</li>
 *   <li>{@link SourceType#SPARQL} / {@link SourceType#HTTP_SPARQL}
 *       &mdash; emit the raw endpoint URL. Jena's own SERVICE executor
 *       handles the HTTP POST.</li>
 * </ul>
 *
 * <h3>What v0.1 doesn't do</h3>
 * Ordinal cost-based join reorder (v0.1 uses declaration-order),
 * ASK-probe source discovery (v0.1 is static-only), adaptive
 * replanning, property-path source selection. See memo &sect;10.
 *
 * <p>Java sibling of {@code oxigraph-wf/src/wf_federation_rewrite.rs}.
 */
public final class WfFederationRewrite {

    private final FederationRegistry registry;
    @SuppressWarnings("unused")
    private final InvokeRegistry invokes;

    /**
     * @param invokes invoke registry &mdash; unused at v0.1 because
     *     substrate-source expansion is done by the downstream
     *     {@link WfSearchRewrite} pass rather than here, but the
     *     constructor takes it so the v0.2 probe path can allocate
     *     invoke specs for cached probes without a signature change.
     */
    public WfFederationRewrite(final FederationRegistry registry, final InvokeRegistry invokes) {
        this.registry = registry;
        this.invokes = invokes;
    }

    /**
     * Static entry point for the rewrite pipeline. Returns the input
     * unchanged when the federation registry is empty or absent.
     */
    public static Op rewrite(final Op op,
                             final FederationRegistry registry,
                             final InvokeRegistry invokes) {
        if (op == null) return null;
        if (registry == null || registry.isEmpty()) {
            return op;
        }
        return new WfFederationRewrite(registry, invokes).rewrite(op);
    }

    /** Instance entry point. */
    public Op rewrite(final Op op) {
        if (op == null) return null;
        if (registry == null || registry.isEmpty()) {
            return op;
        }
        return Transformer.transform(new FederationTransform(), op);
    }

    // ---------------------------------------------------------------------
    // Transform
    // ---------------------------------------------------------------------

    private final class FederationTransform extends TransformCopy {

        /**
         * Explicit {@code SERVICE} clauses in the source query are
         * left untouched &mdash; the caller opted in. Discard the
         * bottom-up transformed sub-op and return the original
         * {@link OpService}. Safe because the OpBGP transform is
         * side-effect free (no registry mutation, purely functional
         * construction of new algebra nodes).
         */
        @Override
        public Op transform(final OpService opService, final Op subOp) {
            return opService;
        }

        @Override
        public Op transform(final OpBGP opBGP) {
            return rewriteBgp(opBGP);
        }

        @Override
        public Op transform(final OpFilter opFilter, final Op subOp) {
            final ExprList exprs = opFilter.getExprs();
            if (exprs == null || exprs.isEmpty()) {
                return super.transform(opFilter, subOp);
            }
            return pushFiltersIntoServices(exprs, subOp);
        }
    }

    // ---------------------------------------------------------------------
    // BGP rewrite (memo §04 steps 1–3)
    // ---------------------------------------------------------------------

    private Op rewriteBgp(final OpBGP opBGP) {
        final List<Triple> triples = opBGP.getPattern().getList();
        if (triples.isEmpty()) return opBGP;
        final int n = triples.size();

        // Step 2: source assignment per triple.
        final List<List<FederationSource>> assignments = new ArrayList<>(n);
        boolean anyAssigned = false;
        for (Triple t : triples) {
            final Node p = t.getPredicate();
            if (p == null || !p.isURI()) {
                assignments.add(List.of());
                continue;
            }
            final List<FederationSource> sources = registry.findByPredicate(p.getURI());
            assignments.add(sources);
            if (!sources.isEmpty()) anyAssigned = true;
        }
        if (!anyAssigned) return opBGP;

        // Step 3: union-find over triples that (a) resolve to a single
        // source and (b) share a subject or object variable with another
        // such triple. Only same-source components merge; a cross-source
        // shared var stays at the outer join.
        final int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            if (assignments.get(i).size() != 1) continue;
            for (int j = i + 1; j < n; j++) {
                if (assignments.get(j).size() != 1) continue;
                if (!assignments.get(i).get(0).name()
                        .equals(assignments.get(j).get(0).name())) continue;
                if (sharesSubjOrObjVar(triples.get(i), triples.get(j))) {
                    unionFind(parent, i, j);
                }
            }
        }

        // LinkedHashMap keys are iterated in first-seen order so groups
        // are emitted in the same order as the source triples, which
        // keeps the rewritten algebra deterministic across runs.
        final Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        final List<Integer> unregistered = new ArrayList<>();
        final List<Integer> multiSource = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final List<FederationSource> a = assignments.get(i);
            if (a.isEmpty()) {
                unregistered.add(i);
            } else if (a.size() > 1) {
                multiSource.add(i);
            } else {
                final int root = find(parent, i);
                groups.computeIfAbsent(root, k -> new ArrayList<>()).add(i);
            }
        }

        final List<Op> parts = new ArrayList<>();

        // Same-source groups → one SERVICE per group.
        for (List<Integer> group : groups.values()) {
            final FederationSource src = assignments.get(group.get(0)).get(0);
            final BasicPattern bp = new BasicPattern();
            for (int idx : group) bp.add(triples.get(idx));
            parts.add(serviceFor(src, new OpBGP(bp)));
        }

        // Multi-source triples → honest OpUnion over per-source SERVICE
        // (memo §04 step 2).
        for (int idx : multiSource) {
            Op union = null;
            for (FederationSource src : assignments.get(idx)) {
                final BasicPattern bp = new BasicPattern();
                bp.add(triples.get(idx));
                final Op single = serviceFor(src, new OpBGP(bp));
                union = (union == null) ? single : OpUnion.create(union, single);
            }
            parts.add(union);
        }

        // Unregistered leftover: stays local as a plain OpBGP so the
        // local dataset can answer it (or so a downstream pass can).
        if (!unregistered.isEmpty()) {
            final BasicPattern bp = new BasicPattern();
            for (int idx : unregistered) bp.add(triples.get(idx));
            parts.add(new OpBGP(bp));
        }

        return joinAll(parts);
    }

    private static boolean sharesSubjOrObjVar(final Triple a, final Triple b) {
        final Node aS = a.getSubject();
        final Node aO = a.getObject();
        final Node bS = b.getSubject();
        final Node bO = b.getObject();
        return matchVar(aS, bS) || matchVar(aS, bO)
                || matchVar(aO, bS) || matchVar(aO, bO);
    }

    private static boolean matchVar(final Node x, final Node y) {
        if (x == null || y == null) return false;
        if (!x.isVariable() || !y.isVariable()) return false;
        return x.getName().equals(y.getName());
    }

    private static int find(final int[] parent, final int i) {
        int x = i;
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    private static void unionFind(final int[] parent, final int a, final int b) {
        final int ra = find(parent, a);
        final int rb = find(parent, b);
        if (ra != rb) parent[ra] = rb;
    }

    private static Op joinAll(final List<Op> parts) {
        if (parts.isEmpty()) return new OpBGP();
        Op acc = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            acc = OpJoin.create(acc, parts.get(i));
        }
        return acc;
    }

    // ---------------------------------------------------------------------
    // SERVICE URL synthesis (memo §06)
    // ---------------------------------------------------------------------

    private static Op serviceFor(final FederationSource src, final Op body) {
        return new OpService(NodeFactory.createURI(serviceUri(src)), body, resolveSilent(src));
    }

    /**
     * Substrate types get the URL-sugar spelling; SPARQL / HTTP_SPARQL
     * get the raw endpoint. Package-private for the tests.
     */
    static String serviceUri(final FederationSource src) {
        return switch (src.sourceType()) {
            case WF_SEARCH   -> "wf-search:"   + src.name();
            case WF_FETCH    -> "wf-fetch:"    + src.name();
            case WF_DOCUMENT -> "wf-document:" + src.name();
            // wf_vector v0.1 — the URL sugar is wf-vector:<name>; Jena
            // has no native vector index in v0.1 (wf-vector memo §10 —
            // native co-located indexes on Jena/RDF4J/QLever are v0.2+),
            // so the emitted URL stays unfolded here and the query will
            // error unless a wf-vector-capable backend is federated in
            // some other way.
            case WF_VECTOR   -> "wf-vector:"   + src.name();
            case SPARQL, HTTP_SPARQL -> src.endpoint();
        };
    }

    /**
     * Resolve the {@code SERVICE SILENT} flag for {@code src} per memo
     * &sect;08. Explicit {@code silent} on the registry entry wins;
     * otherwise fall back to the per-source-type default:
     *
     * <ul>
     *   <li>{@code SPARQL} / {@code HTTP_SPARQL} &rarr; {@code true}
     *       (network endpoint; transport errors degrade to empty
     *       bindings rather than fail the whole query, honest since
     *       static-mode has no probing).</li>
     *   <li>{@code WF_SEARCH} / {@code WF_FETCH} / {@code WF_DOCUMENT}
     *       &rarr; {@code false} (substrate-local dispatch; a failure
     *       is a real bug the operator should see, not a network flap
     *       to mask).</li>
     * </ul>
     *
     * Package-private for the tests.
     */
    static boolean resolveSilent(final FederationSource src) {
        return src.silent().orElseGet(() -> defaultSilentFor(src.sourceType()));
    }

    private static boolean defaultSilentFor(final SourceType type) {
        return switch (type) {
            case SPARQL, HTTP_SPARQL -> true;
            // WF_VECTOR joins the substrate-local group — a KNN dispatch
            // failure inside the embedded index (or an unfolded
            // wf-vector: URL on a non-Oxigraph engine that has no
            // handler for it) is a real bug the operator should see, not
            // a network flap to mask (wf-vector memo §09).
            case WF_SEARCH, WF_FETCH, WF_DOCUMENT, WF_VECTOR -> false;
        };
    }

    // ---------------------------------------------------------------------
    // Filter pushdown (memo §04 step 5)
    // ---------------------------------------------------------------------

    /**
     * Walk the immediate join tree under {@code subOp}, collect the
     * {@code OpService} leaves, and for each filter expression pick the
     * first service whose visible variables cover the expression's free
     * variables. Push into that service; leave everything else at the
     * outer level.
     */
    private Op pushFiltersIntoServices(final ExprList exprs, final Op subOp) {
        final List<Op> leaves = flattenJoin(subOp);
        final int m = leaves.size();
        // Precompute visible vars for each service leaf; non-service
        // leaves get a null placeholder so the index alignment stays
        // simple.
        final Set<Var>[] serviceVars = allocateVarSlots(m);
        for (int i = 0; i < m; i++) {
            if (leaves.get(i) instanceof OpService svc) {
                serviceVars[i] = OpVars.visibleVars(svc.getSubOp());
            }
        }

        final ExprList remaining = new ExprList();
        final List<ExprList> pushed = new ArrayList<>(m);
        for (int i = 0; i < m; i++) pushed.add(new ExprList());

        for (Expr e : exprs) {
            final Set<Var> free = e.getVarsMentioned();
            int host = -1;
            for (int i = 0; i < m; i++) {
                if (serviceVars[i] == null) continue;
                if (serviceVars[i].containsAll(free)) { host = i; break; }
            }
            if (host >= 0) {
                pushed.get(host).add(e);
            } else {
                remaining.add(e);
            }
        }

        boolean anyPushed = false;
        for (ExprList el : pushed) {
            if (!el.isEmpty()) { anyPushed = true; break; }
        }
        if (!anyPushed) {
            // Nothing pushable — behave like the identity transform.
            return OpFilter.filterBy(exprs, subOp);
        }

        // Rebuild the join tree with per-service filters spliced in.
        final List<Op> rebuilt = new ArrayList<>(m);
        for (int i = 0; i < m; i++) {
            Op leaf = leaves.get(i);
            if (serviceVars[i] != null && !pushed.get(i).isEmpty()) {
                final OpService svc = (OpService) leaf;
                final Op newBody = OpFilter.filterBy(pushed.get(i), svc.getSubOp());
                leaf = new OpService(svc.getService(), newBody, svc.getSilent());
            }
            rebuilt.add(leaf);
        }
        final Op joined = joinAll(rebuilt);
        if (remaining.isEmpty()) return joined;
        return OpFilter.filterBy(remaining, joined);
    }

    @SuppressWarnings("unchecked")
    private static Set<Var>[] allocateVarSlots(final int m) {
        return (Set<Var>[]) new Set<?>[m];
    }

    /**
     * Flatten a right-leaning (or arbitrary) {@link OpJoin} tree into its
     * leaves. Anything that isn't an {@code OpJoin} is a leaf. Preserves
     * left-to-right leaf order.
     */
    private static List<Op> flattenJoin(final Op op) {
        final List<Op> out = new ArrayList<>();
        flattenJoinInto(op, out);
        return out;
    }

    private static void flattenJoinInto(final Op op, final List<Op> out) {
        if (op instanceof OpJoin j) {
            flattenJoinInto(j.getLeft(), out);
            flattenJoinInto(j.getRight(), out);
        } else {
            out.add(op);
        }
    }
}
