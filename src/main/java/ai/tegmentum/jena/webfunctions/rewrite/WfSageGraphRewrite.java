package ai.tegmentum.jena.webfunctions.rewrite;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVisitorBase;
import org.apache.jena.sparql.algebra.OpWalker;
import org.apache.jena.sparql.algebra.TransformCopy;
import org.apache.jena.sparql.algebra.Transformer;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpService;
import org.apache.jena.sparql.algebra.op.OpTriple;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * URL-sugar rewrite for {@code SERVICE <wf-sagegraph:<name>?node=<uri>&k=N>}
 * — fold the sugar into a {@code SERVICE <wf-invoke:<hex>>} allocation
 * over the locally-registered wf_sagegraph guest wasm.
 *
 * <p>Design memo: {@code wf-conformance/docs/design/wf-sagegraph.md}
 * &sect;04 (Guest ABI), &sect;05 (Wire shape), &sect;11 (Dispatch pattern).
 *
 * <h3>Why guest-dispatch, not federation-to-remote</h3>
 * Unlike {@code wf_vector}, which federates KNN queries to a remote
 * Oxigraph because only Oxigraph carried the native vector index,
 * every engine registers the {@code wf_sagegraph} guest LOCALLY per the
 * wave-8 host-callback pattern. The {@code wf:sagegraph/host@0.1.0#
 * execute-query} import is wired in
 * {@link ai.tegmentum.jena.webfunctions.JenaWasmInstance}, so this
 * rewrite pass dispatches the guest LOCALLY via {@code wf-invoke:<hex>}
 * on the same {@link InvokeRegistry} {@link WfSearchRewrite} uses.
 *
 * <h3>Grammar</h3>
 * <pre>
 *   wf-sagegraph:&lt;name&gt;?node=&lt;iri&gt;[&amp;k=&lt;n&gt;][&amp;model=&lt;url&gt;][&amp;pool=mean|sum|max]
 * </pre>
 *
 * <h3>Positional arg shape</h3>
 * Guest export {@code embed} receives:
 * <ol start="0">
 *   <li>{@code node-iri} — subject IRI from {@code ?node=} (URL-decoded).</li>
 *   <li>{@code model-url} — from {@code ?model=} or the convention default.</li>
 *   <li>{@code k-hops} — from {@code ?k=} or 1 (matches
 *       {@code sagegraph_index::SageGraphRegistry}'s default_k_hops).</li>
 *   <li>{@code opts-json} — serialized {@code embed-opts} record with
 *       {@code dimensions} + {@code pool}.</li>
 * </ol>
 *
 * <h3>Skip conditions</h3>
 * <ul>
 *   <li>{@code $WF_SAGEGRAPH_WASM_URL} unset — no guest to dispatch to;
 *       pass short-circuits before touching the algebra.</li>
 *   <li>SERVICE body doesn't project {@code wf:embedding} — nothing to
 *       bind, so leaving the sugar alone is safer than emitting a
 *       dispatch nobody consumes.</li>
 *   <li>URL fails to parse (missing name, missing {@code node=}) — leave
 *       alone; execution-time SERVICE dispatch surfaces the error.</li>
 * </ul>
 *
 * <p>Java sibling of {@code qlever-wf-runtime::wf_sagegraph_rewrite} and
 * {@code oxigraph-wf::wf_sagegraph_rewrite::rewrite_query_guest_dispatch}.
 */
public final class WfSageGraphRewrite {

    /** SERVICE URI scheme this pass matches. */
    public static final String WF_SAGEGRAPH_SCHEME = "wf-sagegraph:";
    /** wf_sagegraph guest export invoked on the on-the-fly inference path. */
    private static final String EMBED_ENTRY_POINT = "embed";
    /** wf namespace and the two output-projection predicates. */
    private static final String WF_NS = "http://tegmentum.ai/ns/webfunction/";
    private static final String WF_EMBEDDING = WF_NS + "embedding";
    /**
     * Default model URL when the URL sugar doesn't set {@code ?model=}.
     * The v0.2 stubbed ONNX projection uses this as a hash seed only.
     */
    private static final String DEFAULT_MODEL_URL = "wf-sagegraph:stubbed-model";
    /** WIT-declared default {@code default_k_hops} when the URL omits {@code ?k=}. */
    private static final int DEFAULT_K_HOPS = 1;
    /** WIT-declared default {@code dimensions} for the opts JSON. */
    private static final int DEFAULT_DIMENSIONS = 8;
    /** WIT-declared default {@code pool} on the guest's aggregation kernel. */
    private static final String DEFAULT_POOL = "mean";

    private final InvokeRegistry invokes;
    private final String wasmUrl;

    public WfSageGraphRewrite(final InvokeRegistry invokes, final String wasmUrl) {
        this.invokes = invokes;
        this.wasmUrl = wasmUrl;
    }

    /**
     * Static entry point for the pipeline. Returns the input unchanged
     * when {@code invokes} is null or {@code wasmUrl} is null/empty
     * (unconfigured deployment — no guest to dispatch to).
     */
    public static Op rewrite(final Op op, final InvokeRegistry invokes, final String wasmUrl) {
        if (op == null) return null;
        if (invokes == null) return op;
        if (wasmUrl == null || wasmUrl.isEmpty()) return op;
        return new WfSageGraphRewrite(invokes, wasmUrl).rewrite(op);
    }

    /** Instance entry point. */
    public Op rewrite(final Op op) {
        if (op == null) return null;
        if (invokes == null) return op;
        if (wasmUrl == null || wasmUrl.isEmpty()) return op;
        return Transformer.transform(new SageGraphTransform(), op);
    }

    // ---------------------------------------------------------------------
    // Transform
    // ---------------------------------------------------------------------

    private final class SageGraphTransform extends TransformCopy {
        @Override
        public Op transform(final OpService opService, final Op subOp) {
            final Node svc = opService.getService();
            if (svc == null || !svc.isURI()) {
                return super.transform(opService, subOp);
            }
            final String uri = svc.getURI();
            if (!uri.startsWith(WF_SAGEGRAPH_SCHEME)) {
                return super.transform(opService, subOp);
            }
            final ParsedUrl parsed = parseUrl(uri);
            if (parsed == null) {
                return super.transform(opService, subOp);
            }
            if (!bodyProjectsEmbedding(subOp)) {
                return super.transform(opService, subOp);
            }
            // Capture the projection map BEFORE Jena substitutes outer
            // bindings into the SERVICE body at dispatch time. Same
            // pattern as WfSearchRewrite / WfDocumentRewrite.
            final Map<String, String> projection = collectOutputProjection(subOp);
            final String invokeIri = allocateGuestInvoke(parsed, projection);
            final Node newSvc = NodeFactory.createURI(invokeIri);
            return new OpService(newSvc, subOp, opService.getSilent());
        }
    }

    // ---------------------------------------------------------------------
    // URL parsing
    // ---------------------------------------------------------------------

    /** Result of a successful {@code wf-sagegraph:} URL parse. */
    static final class ParsedUrl {
        final String name;
        final String nodeIri;
        /** null when {@code ?k=} isn't set. */
        final Integer k;
        /** null when {@code ?model=} isn't set. */
        final String model;
        /** null when {@code ?pool=} isn't set. */
        final String pool;
        /**
         * wave-15 (wf-sagegraph memo §06): null when {@code ?features=}
         * isn't set. When set to {@code "text"} the guest routes through
         * {@code wf:embed/host@0.1.0::embed-text} instead of the
         * structural + ONNX default.
         */
        final String features;
        /**
         * wave-15: null when neither {@code ?text-model=} nor
         * {@code ?text_model=} is set. Sentence-embedding model name
         * for text-mode.
         */
        final String textModel;
        /**
         * wave-15: null when neither {@code ?text-predicate=} nor
         * {@code ?text_predicate=} is set. Predicate IRI to source
         * the text signal from.
         */
        final String textPredicate;

        ParsedUrl(final String name, final String nodeIri, final Integer k,
                  final String model, final String pool,
                  final String features, final String textModel,
                  final String textPredicate) {
            this.name = name;
            this.nodeIri = nodeIri;
            this.k = k;
            this.model = model;
            this.pool = pool;
            this.features = features;
            this.textModel = textModel;
            this.textPredicate = textPredicate;
        }
    }

    static ParsedUrl parseUrl(final String uri) {
        if (!uri.startsWith(WF_SAGEGRAPH_SCHEME)) return null;
        final String rest = uri.substring(WF_SAGEGRAPH_SCHEME.length());
        if (rest.isEmpty()) return null;
        final int qIdx = rest.indexOf('?');
        final String name;
        final String optsStr;
        if (qIdx >= 0) {
            name = rest.substring(0, qIdx);
            optsStr = rest.substring(qIdx + 1);
        } else {
            name = rest;
            optsStr = null;
        }
        if (name.isEmpty()) return null;
        final Map<String, String> opts = new LinkedHashMap<>();
        if (optsStr != null && !optsStr.isEmpty()) {
            for (String pair : optsStr.split("&")) {
                if (pair.isEmpty()) continue;
                final int eq = pair.indexOf('=');
                final String key;
                final String value;
                if (eq < 0) {
                    key = urlDecode(pair);
                    value = "";
                } else {
                    key = urlDecode(pair.substring(0, eq));
                    value = urlDecode(pair.substring(eq + 1));
                }
                opts.put(key, value);
            }
        }
        final String nodeIri = opts.get("node");
        if (nodeIri == null || nodeIri.isEmpty()) return null;
        Integer k = null;
        final String kStr = opts.get("k");
        if (kStr != null && !kStr.isEmpty()) {
            try {
                k = Integer.parseUnsignedInt(kStr);
            } catch (NumberFormatException ignored) {
                // Malformed — treat as absent, dispatch will use the default.
            }
        }
        // wave-15 text-mode opts. Accept both kebab-case (WIT-native
        // shape the guest deserializes) and snake-case for URL-shape
        // parity with engines that emit either convention. Kebab wins
        // when both present.
        final String features = opts.get("features");
        String textModel = opts.get("text-model");
        if (textModel == null) textModel = opts.get("text_model");
        String textPredicate = opts.get("text-predicate");
        if (textPredicate == null) textPredicate = opts.get("text_predicate");
        return new ParsedUrl(name, nodeIri, k, opts.get("model"), opts.get("pool"),
                features, textModel, textPredicate);
    }

    private static String urlDecode(final String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    // ---------------------------------------------------------------------
    // SERVICE body inspection
    // ---------------------------------------------------------------------

    /**
     * True when the SERVICE body contains at least one
     * {@code ?_ wf:embedding ?var} triple. Absence means the caller
     * isn't going to bind the guest's return anywhere, so the fold is
     * a no-op.
     */
    private static boolean bodyProjectsEmbedding(final Op body) {
        final boolean[] found = {false};
        if (body == null) return false;
        OpWalker.walk(body, new OpVisitorBase() {
            void consider(final Triple t) {
                if (found[0]) return;
                final Node p = t.getPredicate();
                if (p == null || !p.isURI()) return;
                if (!WF_EMBEDDING.equals(p.getURI())) return;
                final Node o = t.getObject();
                if (o != null && o.isVariable()) {
                    found[0] = true;
                }
            }

            @Override
            public void visit(final OpBGP opBGP) {
                for (Triple t : opBGP.getPattern()) consider(t);
            }

            @Override
            public void visit(final OpTriple opTriple) {
                consider(opTriple.getTriple());
            }
        });
        return found[0];
    }

    /**
     * Walk the SERVICE body and collect every {@code ?_ wf:<col> ?var}
     * triple as a (guest_col -> outer_var) rename entry — mirrors the
     * companion pass in {@link WfSearchRewrite} so the wf-invoke
     * dispatcher ({@link ai.tegmentum.jena.webfunctions.WfInvokeService})
     * can rename the guest-emitted columns onto the outer-query
     * variables the caller declared. Called at rewrite time so the map
     * is captured BEFORE Jena's per-outer-binding substitution rewrites
     * {@code wf:node ?person} into {@code wf:node <http://ex/alice>}.
     */
    private static Map<String, String> collectOutputProjection(final Op body) {
        final Map<String, String> out = new LinkedHashMap<>();
        if (body == null) return out;
        OpWalker.walk(body, new OpVisitorBase() {
            void consider(final Triple t) {
                final Node p = t.getPredicate();
                if (p == null || !p.isURI()) return;
                final String pUri = p.getURI();
                if (!pUri.startsWith(WF_NS)) return;
                final String col = pUri.substring(WF_NS.length());
                // Skip config-side predicates the wf:call envelope uses.
                if ("wasm".equals(col) || "arg".equals(col)
                        || "call".equals(col) || "query".equals(col)) {
                    return;
                }
                final Node obj = t.getObject();
                if (obj == null || !obj.isVariable()) return;
                out.put(col, obj.getName());
            }

            @Override
            public void visit(final OpBGP opBGP) {
                for (Triple t : opBGP.getPattern()) consider(t);
            }

            @Override
            public void visit(final OpTriple opTriple) {
                consider(opTriple.getTriple());
            }
        });
        return out;
    }

    // ---------------------------------------------------------------------
    // InvokeSpec allocation
    // ---------------------------------------------------------------------

    private String allocateGuestInvoke(final ParsedUrl parsed,
                                       final Map<String, String> projection) {
        final int k = parsed.k == null ? DEFAULT_K_HOPS : parsed.k;
        final String model = parsed.model == null ? DEFAULT_MODEL_URL : parsed.model;
        final String pool = parsed.pool == null ? DEFAULT_POOL : parsed.pool;
        // Build opts JSON with `text-*` fields (kebab-case, matching
        // the WIT `embed-opts` record) only when the URL sugar supplied
        // them so the wire shape stays byte-stable for the structural
        // default path.
        final StringBuilder opts = new StringBuilder();
        opts.append("{\"dimensions\":").append(DEFAULT_DIMENSIONS);
        opts.append(",\"pool\":\"").append(jsonEscape(pool)).append("\"");
        if (parsed.features != null) {
            opts.append(",\"features\":\"").append(jsonEscape(parsed.features)).append("\"");
        }
        if (parsed.textModel != null) {
            opts.append(",\"text-model\":\"").append(jsonEscape(parsed.textModel)).append("\"");
        }
        if (parsed.textPredicate != null) {
            opts.append(",\"text-predicate\":\"")
                    .append(jsonEscape(parsed.textPredicate)).append("\"");
        }
        opts.append("}");
        final String optsJson = opts.toString();
        final List<Node> args = new ArrayList<>(4);
        args.add(NodeFactory.createLiteralString(parsed.nodeIri));
        args.add(NodeFactory.createLiteralString(model));
        args.add(NodeFactory.createLiteralString(Integer.toString(k)));
        args.add(NodeFactory.createLiteralString(optsJson));
        final long id = invokes.insert(
                new InvokeRegistry.InvokeSpec(wasmUrl, args, EMBED_ENTRY_POINT, projection));
        return InvokeRegistry.iriFor(id);
    }

    private static String jsonEscape(final String s) {
        final StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n");  break;
                case '\r': out.append("\\r");  break;
                case '\t': out.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
