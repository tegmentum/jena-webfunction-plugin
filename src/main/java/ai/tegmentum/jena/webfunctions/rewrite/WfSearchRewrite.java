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
import java.util.Locale;
import java.util.Map;

/**
 * URL-sugar rewrite: expand
 * {@code SERVICE <wf-search:name[@time-spec][?opts]>} into a
 * {@code SERVICE <wf-invoke:hex>} allocation with the registry entry's
 * config baked in.
 *
 * <p>Design memo: {@code wf-conformance/docs/design/wf-document-v1.md}
 * &sect;05 (URL grammar) and &sect;10 (implementation notes).
 *
 * <h3>Grammar</h3>
 * <pre>
 *   wf-search:&lt;name&gt;[@&lt;time-spec&gt;][?&lt;opt&gt;=&lt;value&gt;&amp;...]
 *   time-spec ::= ISO-8601-UTC | "rev" &lt;N&gt;
 * </pre>
 *
 * <p>Recognised opt keys: {@code highlight}, {@code lang}, {@code filter},
 * {@code limit}, {@code offset}, {@code include_body}. Any other key is
 * silently ignored so operators can drop future keys in without a
 * planner-level upgrade.
 *
 * <h3>Skip conditions</h3>
 * <ul>
 *   <li>The registered {@code name} isn't in the {@link DocumentRegistry}
 *       (or the registry is empty).</li>
 *   <li>The SERVICE body has no {@code wf:query "value"} triple — the
 *       sugar carries no search string, so there's nothing to invoke.</li>
 * </ul>
 * Both are pass-throughs, not errors — a misconfigured name should
 * surface as a normal SPARQL "no such service" at execution time, not a
 * plan-time crash.
 *
 * <p>Java sibling of {@code oxigraph-wf/src/wf_search_rewrite.rs} and the
 * RDF4J port at
 * {@code rdf4j-webfunction-plugin/src/main/java/ai/tegmentum/rdf4j/webfunctions/rewrite/WfSearchRewrite.java}.
 */
public final class WfSearchRewrite {

    static final String WF_NS = "http://tegmentum.ai/ns/webfunction/";
    static final String WF_QUERY = WF_NS + "query";

    /** The URL scheme this pass recognises at the SERVICE position. */
    public static final String WF_SEARCH_SCHEME = "wf-search:";

    /** Default limit when {@code ?limit=N} is not supplied via the URL. */
    private static final int DEFAULT_LIMIT = 20;

    private final DocumentRegistry registry;
    private final InvokeRegistry invokes;

    public WfSearchRewrite(final DocumentRegistry registry, final InvokeRegistry invokes) {
        this.registry = registry;
        this.invokes = invokes;
    }

    /**
     * Static entry point for the pipeline. Returns the input unchanged
     * when the document registry is empty or absent.
     */
    public static Op rewrite(final Op op,
                             final DocumentRegistry registry,
                             final InvokeRegistry invokes) {
        if (op == null) return null;
        if (registry == null || registry.isEmpty() || invokes == null) {
            return op;
        }
        return new WfSearchRewrite(registry, invokes).rewrite(op);
    }

    /** Instance entry point. */
    public Op rewrite(final Op op) {
        if (op == null) return null;
        if (registry == null || registry.isEmpty() || invokes == null) {
            return op;
        }
        return Transformer.transform(new SearchTransform(), op);
    }

    // ---------------------------------------------------------------------
    // Transform
    // ---------------------------------------------------------------------

    private final class SearchTransform extends TransformCopy {
        @Override
        public Op transform(final OpService opService, final Op subOp) {
            final Node svc = opService.getService();
            if (svc == null || !svc.isURI()) {
                return super.transform(opService, subOp);
            }
            final String uri = svc.getURI();
            if (!uri.startsWith(WF_SEARCH_SCHEME)) {
                return super.transform(opService, subOp);
            }

            final ParsedUrl parsed = parseUrl(uri);
            if (parsed == null) {
                return super.transform(opService, subOp);
            }

            final DocumentRegistry.DocumentIndex entry = registry.byName(parsed.name);
            if (entry == null) {
                // Unregistered name -> pass through; execution-time
                // SERVICE dispatch will raise a proper "no such handler"
                // error rather than us silently substituting a bogus IRI.
                return super.transform(opService, subOp);
            }

            final String query = findQueryLiteral(subOp);
            if (query == null) {
                // No wf:query triple in the body -> nothing to invoke on.
                return super.transform(opService, subOp);
            }

            final String iri = allocateInvoke(entry, parsed, query);
            final Node newSvc = NodeFactory.createURI(iri);
            return new OpService(newSvc, subOp, opService.getSilent());
        }
    }

    // ---------------------------------------------------------------------
    // URL parsing
    // ---------------------------------------------------------------------

    /** Result of a successful {@code wf-search:} URL parse. */
    static final class ParsedUrl {
        final String name;
        /** ISO-8601 UTC time-spec, or null when absent / when {@link #atRev} is set. */
        final String atTime;
        /** Numeric revision from {@code @rev<N>}, or null when absent / when {@link #atTime} is set. */
        final Long atRev;
        /** Preserved insertion order for stable JSON emission. */
        final Map<String, String> opts;

        ParsedUrl(final String name, final String atTime, final Long atRev,
                  final Map<String, String> opts) {
            this.name = name;
            this.atTime = atTime;
            this.atRev = atRev;
            this.opts = opts;
        }
    }

    /**
     * Handwritten parser: URI class treats {@code wf-search:...} as an
     * opaque URI (no authority component) so it exposes the whole
     * {@code name@time?opts} lump as one string. Splitting by hand is
     * simpler than pretending it's hierarchical.
     */
    static ParsedUrl parseUrl(final String uri) {
        if (!uri.startsWith(WF_SEARCH_SCHEME)) return null;
        String rest = uri.substring(WF_SEARCH_SCHEME.length());
        if (rest.isEmpty()) return null;

        // 1) Split off the opts query-string.
        String optsPart = null;
        final int qIdx = rest.indexOf('?');
        if (qIdx >= 0) {
            optsPart = rest.substring(qIdx + 1);
            rest = rest.substring(0, qIdx);
        }

        // 2) Split off the time-spec.
        String timePart = null;
        final int atIdx = rest.indexOf('@');
        if (atIdx >= 0) {
            timePart = rest.substring(atIdx + 1);
            rest = rest.substring(0, atIdx);
        }

        // 3) What's left is the name. Reject an empty name outright.
        final String name = rest;
        if (name.isEmpty()) return null;

        String atTime = null;
        Long atRev = null;
        if (timePart != null && !timePart.isEmpty()) {
            if (timePart.startsWith("rev")) {
                try {
                    atRev = Long.parseUnsignedLong(timePart.substring(3));
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                // Treat as ISO-8601; guest validates the literal shape.
                atTime = timePart;
            }
        }

        final Map<String, String> opts = new LinkedHashMap<>();
        if (optsPart != null && !optsPart.isEmpty()) {
            for (String pair : optsPart.split("&")) {
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
                if (isRecognisedOpt(key)) {
                    opts.put(key, value);
                }
            }
        }
        return new ParsedUrl(name, atTime, atRev, opts);
    }

    private static boolean isRecognisedOpt(final String key) {
        switch (key) {
            case "highlight":
            case "lang":
            case "filter":
            case "limit":
            case "offset":
            case "include_body":
                return true;
            default:
                return false;
        }
    }

    private static String urlDecode(final String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Malformed %-encoding -> pass the raw text through; guest
            // will surface the problem if it matters.
            return s;
        }
    }

    // ---------------------------------------------------------------------
    // SERVICE-body scan for wf:query
    // ---------------------------------------------------------------------

    /**
     * Walk the SERVICE body looking for a triple whose predicate is
     * {@link #WF_QUERY} and whose object is a literal. Return the
     * lexical form of that literal, or null when no such triple exists.
     */
    private static String findQueryLiteral(final Op body) {
        final String[] found = new String[1];
        OpWalker.walk(body, new OpVisitorBase() {
            void consider(final Triple t) {
                if (found[0] != null) return;
                final Node p = t.getPredicate();
                if (p == null || !p.isURI()) return;
                if (!WF_QUERY.equals(p.getURI())) return;
                final Node o = t.getObject();
                if (o == null || !o.isLiteral()) return;
                found[0] = o.getLiteralLexicalForm();
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

    // ---------------------------------------------------------------------
    // Opts JSON + InvokeSpec allocation
    // ---------------------------------------------------------------------

    private String allocateInvoke(final DocumentRegistry.DocumentIndex entry,
                                  final ParsedUrl parsed,
                                  final String query) {
        final int limit = resolveLimit(parsed);
        final String optsJson = buildOptsJson(parsed, limit);

        final List<Node> args = new ArrayList<>(6);
        args.add(NodeFactory.createLiteralString(entry.searchBackend()));
        args.add(NodeFactory.createLiteralString(entry.storageBackend()));
        args.add(NodeFactory.createLiteralString(entry.searchIndex()));
        args.add(NodeFactory.createLiteralString(query));
        args.add(NodeFactory.createLiteralString(Integer.toString(limit)));
        args.add(NodeFactory.createLiteralString(optsJson));

        final long id = invokes.insert(
                new InvokeRegistry.InvokeSpec(entry.guestUrl(), args, "search"));
        return InvokeRegistry.iriFor(id);
    }

    private static int resolveLimit(final ParsedUrl parsed) {
        final String s = parsed.opts.get("limit");
        if (s == null || s.isEmpty()) return DEFAULT_LIMIT;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }

    /**
     * Query-time opts JSON, hand-built for a stable, testable key order:
     * {@code {"limit":N[,"offset":N][,"highlight":true|false][,"lang":"..."]
     * [,"filter":"..."][,"include_body":true|false][,"at_time":"..."]
     * [,"at_rev":N]}}.
     *
     * <p>The {@code at_time}/{@code at_rev} bake-in is the whole point of
     * the sugar — the URL's {@code @time-spec} winds up here so the guest
     * doesn't need to re-parse the URL.
     */
    private static String buildOptsJson(final ParsedUrl parsed, final int limit) {
        final StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"limit\":").append(limit);
        appendIntIfPresent(sb, parsed.opts.get("offset"), "offset");
        appendBoolIfPresent(sb, parsed.opts.get("highlight"), "highlight");
        appendStrIfPresent(sb, parsed.opts.get("lang"), "lang");
        appendStrIfPresent(sb, parsed.opts.get("filter"), "filter");
        appendBoolIfPresent(sb, parsed.opts.get("include_body"), "include_body");
        if (parsed.atTime != null) {
            sb.append(",\"at_time\":\"").append(jsonEscape(parsed.atTime)).append('"');
        }
        if (parsed.atRev != null) {
            sb.append(",\"at_rev\":").append(parsed.atRev.longValue());
        }
        sb.append('}');
        return sb.toString();
    }

    private static void appendIntIfPresent(final StringBuilder sb, final String v, final String key) {
        if (v == null || v.isEmpty()) return;
        try {
            final long n = Long.parseLong(v);
            sb.append(",\"").append(key).append("\":").append(n);
        } catch (NumberFormatException ignored) {
            // Non-numeric -> drop.
        }
    }

    private static void appendBoolIfPresent(final StringBuilder sb, final String v, final String key) {
        if (v == null || v.isEmpty()) return;
        final boolean b = "true".equalsIgnoreCase(v) || "1".equals(v);
        sb.append(",\"").append(key).append("\":").append(b);
    }

    private static void appendStrIfPresent(final StringBuilder sb, final String v, final String key) {
        if (v == null || v.isEmpty()) return;
        sb.append(",\"").append(key).append("\":\"").append(jsonEscape(v)).append('"');
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
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
