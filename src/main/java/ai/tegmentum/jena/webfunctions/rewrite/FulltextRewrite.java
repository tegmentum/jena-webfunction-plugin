package ai.tegmentum.jena.webfunctions.rewrite;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVisitorBase;
import org.apache.jena.sparql.algebra.OpWalker;
import org.apache.jena.sparql.algebra.TransformCopy;
import org.apache.jena.sparql.algebra.Transformer;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpFilter;
import org.apache.jena.sparql.algebra.op.OpJoin;
import org.apache.jena.sparql.algebra.op.OpService;
import org.apache.jena.sparql.algebra.op.OpTriple;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.expr.E_Equals;
import org.apache.jena.sparql.expr.E_Function;
import org.apache.jena.sparql.expr.E_Lang;
import org.apache.jena.sparql.expr.E_LangMatches;
import org.apache.jena.sparql.expr.E_Regex;
import org.apache.jena.sparql.expr.E_Str;
import org.apache.jena.sparql.expr.E_StrContains;
import org.apache.jena.sparql.expr.E_StrLowerCase;
import org.apache.jena.sparql.expr.E_StrStartsWith;
import org.apache.jena.sparql.expr.E_StrUpperCase;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprFunction1;
import org.apache.jena.sparql.expr.ExprFunction2;
import org.apache.jena.sparql.expr.ExprList;
import org.apache.jena.sparql.expr.NodeValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Filter-fold rewrite: lift ordinary
 * {@code FILTER(REGEX | CONTAINS | STRSTARTS | LANG = ... | LANGMATCHES)}
 * clauses over registered literal-index predicates into a
 * {@code wf-invoke:<hex>} SERVICE dispatch.
 *
 * <p>Design memo: {@code wf-conformance/docs/design/wf-fulltext.md} &sect;06.
 * Rewrite target vocabulary: memo &sect;08 ({@code ?_ wf:doc ?subj} envelope).
 *
 * <h3>Safety invariant (memo &sect;06)</h3>
 *
 * The rewrite is a <b>superset</b> guarantee. The fulltext SERVICE
 * returns a <i>candidate set</i>; the original FILTER is preserved
 * around the joined result so ARQ's own comparison re-checks each
 * candidate. Never lose rows: every fold is provably a superset of the
 * FILTER's row-set. Never add rows: the FILTER stays as a
 * candidate-check.
 *
 * <p>Java port of {@code oxigraph-wf/src/fulltext_rewrite.rs}, mirroring
 * the RDF4J port at
 * {@code rdf4j-webfunction-plugin/src/main/java/ai/tegmentum/rdf4j/webfunctions/rewrite/FulltextRewrite.java}.
 */
public final class FulltextRewrite {

    /** wf namespace for the envelope predicate (matches ShapeRewrite and memo &sect;08). */
    static final String WF_NS = "http://tegmentum.ai/ns/webfunction/";
    static final String WF_DOC = WF_NS + "doc";

    // XPath function URIs — Jena has dedicated E_* classes for the SPARQL
    // built-ins we care about, but users can also invoke the XPath fn: URIs
    // directly via E_Function. Handle both spellings for parity with the
    // RDF4J port.
    private static final String FN_CONTAINS   = "http://www.w3.org/2005/xpath-functions#contains";
    private static final String FN_STARTSWITH = "http://www.w3.org/2005/xpath-functions#starts-with";
    private static final String FN_LOWER_CASE = "http://www.w3.org/2005/xpath-functions#lower-case";
    private static final String FN_UPPER_CASE = "http://www.w3.org/2005/xpath-functions#upper-case";

    private final FulltextRegistry registry;
    private final InvokeRegistry invokeRegistry;
    private int folds;

    public FulltextRewrite(final FulltextRegistry registry, final InvokeRegistry invokeRegistry) {
        this.registry = registry;
        this.invokeRegistry = invokeRegistry;
    }

    public int foldCount() { return folds; }

    /**
     * Convenience static entry point for the rewrite pipeline. Returns
     * the input unchanged when the fulltext registry is empty or absent.
     */
    public static Op rewrite(final Op op,
                             final FulltextRegistry registry,
                             final InvokeRegistry invokeRegistry) {
        if (op == null) return null;
        if (registry == null || registry.isEmpty() || invokeRegistry == null) {
            return op;
        }
        return new FulltextRewrite(registry, invokeRegistry).rewrite(op);
    }

    /** Instance entry point — walks the tree, updates {@link #foldCount()}. */
    public Op rewrite(final Op op) {
        if (op == null) return null;
        if (registry == null || registry.isEmpty() || invokeRegistry == null) {
            return op;
        }
        return Transformer.transform(new FoldTransform(), op);
    }

    // ---------------------------------------------------------------------
    // Transform
    // ---------------------------------------------------------------------

    private final class FoldTransform extends TransformCopy {
        @Override
        public Op transform(final OpFilter opFilter, final Op subOp) {
            // TransformCopy is bottom-up: subOp is already transformed —
            // nested filter/join fold attempts have already happened.
            final ExprList exprs = opFilter.getExprs();
            if (exprs == null || exprs.isEmpty()) {
                return super.transform(opFilter, subOp);
            }

            // Try each expression in the ExprList. First match wins;
            // subsequent expressions stay in the outer OpFilter as the
            // safety-invariant candidate re-check.
            for (Expr expr : exprs) {
                final FoldCandidate candidate = analyzeFilter(expr);
                if (candidate == null) continue;
                final TargetTriple target = findTargetTriple(subOp, candidate.varName);
                if (target == null) continue;
                final FulltextRegistry.FulltextIndex entry = registry.findByPredicate(target.predicateIri);
                if (entry == null) continue;
                if (!candidate.compatibleWithIndex(entry)) continue;

                final String iri = allocateInvoke(candidate, entry);
                final Op envelope = buildEnvelope(target.subjectVarName);
                final OpService service = new OpService(NodeFactory.createURI(iri), envelope, false);
                final Op joined = OpJoin.create(subOp, service);
                folds++;
                // Preserve the entire ExprList — the safety invariant
                // requires every original condition to re-check.
                return OpFilter.filterBy(exprs, joined);
            }
            return super.transform(opFilter, subOp);
        }
    }

    // ---------------------------------------------------------------------
    // Filter analysis
    // ---------------------------------------------------------------------

    /** Case-hint recovered from a wrapper unwrap. */
    private enum CaseHint {
        NONE, CASE_INSENSITIVE;

        CaseHint merge(final CaseHint other) {
            if (this == CASE_INSENSITIVE || other == CASE_INSENSITIVE) return CASE_INSENSITIVE;
            return NONE;
        }
        boolean isCi() { return this == CASE_INSENSITIVE; }
    }

    /** One recognised foldable filter shape. */
    private static final class FoldCandidate {
        final String varName;
        final String query;
        final String lang; // nullable
        final CaseHint caseHint;

        FoldCandidate(final String varName, final String query, final String lang, final CaseHint caseHint) {
            this.varName = varName;
            this.query = query;
            this.lang = lang;
            this.caseHint = caseHint;
        }

        boolean compatibleWithIndex(final FulltextRegistry.FulltextIndex entry) {
            if (lang == null) return true;
            for (String l : entry.languages()) if (l.equals(lang)) return true;
            return false;
        }
    }

    private FoldCandidate analyzeFilter(final Expr expr) {
        if (expr == null) return null;

        // REGEX(?x, "pat"[, "flags"])
        if (expr instanceof E_Regex regex) {
            return analyzeRegex(regex);
        }

        // Dedicated CONTAINS
        if (expr instanceof E_StrContains contains) {
            return analyzeContainsLike(contains.getArg1(), contains.getArg2(), true);
        }
        // Dedicated STRSTARTS
        if (expr instanceof E_StrStartsWith starts) {
            return analyzeContainsLike(starts.getArg1(), starts.getArg2(), false);
        }

        // FN URI variants (fn:contains, fn:starts-with) via E_Function.
        if (expr instanceof E_Function fn) {
            final String uri = fn.getFunctionIRI();
            final List<Expr> args = fn.getArgs();
            if (FN_CONTAINS.equals(uri) && args != null && args.size() == 2) {
                return analyzeContainsLike(args.get(0), args.get(1), true);
            }
            if (FN_STARTSWITH.equals(uri) && args != null && args.size() == 2) {
                return analyzeContainsLike(args.get(0), args.get(1), false);
            }
        }

        // LANGMATCHES(LANG(?x), "en")
        if (expr instanceof E_LangMatches lm) {
            final String varName = langOfVar(lm.getArg1());
            if (varName == null) return null;
            final String lang = literalArg(lm.getArg2());
            if (lang == null || lang.isEmpty()) return null;
            return new FoldCandidate(varName, "", lang, CaseHint.NONE);
        }

        // LANG(?x) = "en" (either operand order).
        if (expr instanceof E_Equals eq) {
            final FoldCandidate a = tryLangEq(eq.getArg1(), eq.getArg2());
            if (a != null) return a;
            return tryLangEq(eq.getArg2(), eq.getArg1());
        }

        return null;
    }

    /**
     * Shared shape for CONTAINS / STRSTARTS. When {@code wordSafe} is
     * true the substring must be word-safe (length &ge; 4, all alnum);
     * for STRSTARTS a shorter alnum prefix is enough.
     */
    private FoldCandidate analyzeContainsLike(final Expr firstArg,
                                              final Expr secondArg,
                                              final boolean wordSafe) {
        final Unwrap uw = unwrapStringFunctions(firstArg);
        if (uw == null) return null;
        String needle = literalArg(secondArg);
        if (needle == null) return null;
        if (uw.caseHint.isCi()) needle = needle.toLowerCase(Locale.ROOT);
        if (wordSafe) {
            if (!isWordSafeSubstring(needle)) return null;
        } else {
            if (needle.isEmpty()) return null;
        }
        return new FoldCandidate(uw.varName, needle, null, uw.caseHint);
    }

    private FoldCandidate tryLangEq(final Expr maybeLang, final Expr maybeLit) {
        final String varName = langOfVar(maybeLang);
        if (varName == null) return null;
        final String lang = literalArg(maybeLit);
        if (lang == null || lang.isEmpty()) return null;
        return new FoldCandidate(varName, "", lang, CaseHint.NONE);
    }

    private FoldCandidate analyzeRegex(final E_Regex regex) {
        final List<Expr> args = regex.getArgs();
        if (args == null || args.size() < 2) return null;
        final Unwrap uw = unwrapStringFunctions(args.get(0));
        if (uw == null) return null;
        String pat = literalArg(args.get(1));
        if (pat == null) return null;
        if (!isSafeRegexPattern(pat)) return null;

        CaseHint caseHint = uw.caseHint;
        if (args.size() >= 3) {
            final String flags = literalArg(args.get(2));
            // Only "i" is documented as safe by the memo. Anything else
            // (m, s, x) changes semantics we can't match against the index.
            if (!"i".equals(flags)) return null;
            caseHint = caseHint.merge(CaseHint.CASE_INSENSITIVE);
        }
        if (caseHint.isCi()) pat = pat.toLowerCase(Locale.ROOT);
        return new FoldCandidate(uw.varName, pat, null, caseHint);
    }

    /** Return record from wrapper unwrap: which variable and what case hint. */
    private static final class Unwrap {
        final String varName;
        final CaseHint caseHint;
        Unwrap(final String v, final CaseHint c) { this.varName = v; this.caseHint = c; }
    }

    /**
     * Recursively strip case/lexical-form wrappers (LCASE, UCASE, STR)
     * around a bare variable, capping the depth at three. Any other outer
     * shape (CONCAT, SUBSTR, arithmetic) returns null.
     */
    private static Unwrap unwrapStringFunctions(final Expr e) {
        return unwrapGo(e, 3);
    }

    private static Unwrap unwrapGo(final Expr e, final int depth) {
        if (e == null) return null;
        if (e.isVariable()) {
            return new Unwrap(e.getVarName(), CaseHint.NONE);
        }
        if (depth == 0) return null;

        // STR — dedicated class.
        if (e instanceof E_Str s) {
            final Unwrap inner = unwrapGo(s.getArg(), depth - 1);
            if (inner == null) return null;
            return new Unwrap(inner.varName, inner.caseHint); // STR does not imply CI
        }
        // LCASE — dedicated class.
        if (e instanceof E_StrLowerCase lc) {
            final Unwrap inner = unwrapGo(lc.getArg(), depth - 1);
            if (inner == null) return null;
            return new Unwrap(inner.varName, CaseHint.CASE_INSENSITIVE.merge(inner.caseHint));
        }
        // UCASE — dedicated class.
        if (e instanceof E_StrUpperCase uc) {
            final Unwrap inner = unwrapGo(uc.getArg(), depth - 1);
            if (inner == null) return null;
            return new Unwrap(inner.varName, CaseHint.CASE_INSENSITIVE.merge(inner.caseHint));
        }
        // FN URI variants (fn:lower-case, fn:upper-case) via E_Function.
        if (e instanceof E_Function fn) {
            final String uri = fn.getFunctionIRI();
            final CaseHint thisHint;
            if (FN_LOWER_CASE.equals(uri) || FN_UPPER_CASE.equals(uri)) {
                thisHint = CaseHint.CASE_INSENSITIVE;
            } else {
                return null;
            }
            final List<Expr> args = fn.getArgs();
            if (args == null || args.size() != 1) return null;
            final Unwrap inner = unwrapGo(args.get(0), depth - 1);
            if (inner == null) return null;
            return new Unwrap(inner.varName, thisHint.merge(inner.caseHint));
        }
        return null;
    }

    /** If {@code e} is a plain string literal, return its lexical form. */
    private static String literalArg(final Expr e) {
        if (e == null) return null;
        if (!e.isConstant()) return null;
        final NodeValue nv = e.getConstant();
        if (nv == null) return null;
        if (nv.isString() || nv.isLangString()) {
            return nv.getString();
        }
        // Fall back to the node's lexical form if it's a plain literal.
        final Node n = nv.asNode();
        if (n != null && n.isLiteral()) {
            return n.getLiteralLexicalForm();
        }
        return null;
    }

    /** If {@code e} is exactly {@code LANG(?x)}, return the variable name. */
    private static String langOfVar(final Expr e) {
        if (e instanceof E_Lang lang) {
            final Expr inner = lang.getArg();
            if (inner != null && inner.isVariable()) return inner.getVarName();
        }
        // fn:lang has no dedicated Jena URI variant; the built-in maps
        // directly to E_Lang, so no fallback needed here.
        return null;
    }

    // ---------------------------------------------------------------------
    // Safety checks
    // ---------------------------------------------------------------------

    /** Alnum-and-space-and-dash-and-underscore allowlist; must contain an alnum. */
    private static boolean isSafeRegexPattern(final String pat) {
        if (pat.isEmpty()) return false;
        boolean sawAlnum = false;
        for (int i = 0; i < pat.length(); i++) {
            final char c = pat.charAt(i);
            final boolean allowed = Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_';
            if (!allowed) return false;
            if (Character.isLetterOrDigit(c)) sawAlnum = true;
        }
        return sawAlnum;
    }

    /** Word-safe substring: length &ge; 4, all alphanumeric. */
    private static boolean isWordSafeSubstring(final String sub) {
        if (sub.length() < 4) return false;
        for (int i = 0; i < sub.length(); i++) {
            if (!Character.isLetterOrDigit(sub.charAt(i))) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // Target-triple discovery
    // ---------------------------------------------------------------------

    private static final class TargetTriple {
        final String subjectVarName;
        final String predicateIri;
        TargetTriple(final String s, final String p) { this.subjectVarName = s; this.predicateIri = p; }
    }

    /**
     * Find the triple pattern binding {@code varName} as its OBJECT with a
     * concrete-IRI predicate. If multiple such triples exist under
     * different predicates, return null (ambiguous — which index do we
     * hit?).
     */
    private TargetTriple findTargetTriple(final Op inner, final String varName) {
        final class Collector extends OpVisitorBase {
            String pred = null;
            String subj = null;
            boolean aborted = false;

            void consider(final Triple t) {
                if (aborted) return;
                final Node o = t.getObject();
                if (o == null || !o.isVariable()) return;
                if (!varName.equals(o.getName())) return;

                final Node p = t.getPredicate();
                if (p == null || !p.isURI()) { aborted = true; return; }
                final Node s = t.getSubject();
                if (s == null || !s.isVariable()) { aborted = true; return; } // subject must be a variable

                if (pred == null) {
                    pred = p.getURI();
                    subj = s.getName();
                } else if (!pred.equals(p.getURI())) {
                    aborted = true; // multi-predicate binding — refuse
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
        }
        final Collector c = new Collector();
        OpWalker.walk(inner, c);
        if (c.aborted) return null;
        if (c.pred == null) return null;
        return new TargetTriple(c.subj, c.pred);
    }

    // ---------------------------------------------------------------------
    // InvokeSpec + SERVICE envelope
    // ---------------------------------------------------------------------

    private String allocateInvoke(final FoldCandidate candidate, final FulltextRegistry.FulltextIndex entry) {
        final String[] be = splitRegistryOpts(entry);
        final String backendEndpoint = be[0];
        final String indexName       = be[1];
        final String queryOptsJson   = buildQueryOptsJson(candidate.lang, candidate.caseHint);

        final List<Node> args = new ArrayList<>(4);
        args.add(NodeFactory.createLiteralString(backendEndpoint));
        args.add(NodeFactory.createLiteralString(indexName));
        args.add(NodeFactory.createLiteralString(candidate.query));
        args.add(NodeFactory.createLiteralString(queryOptsJson));

        // wf_fulltext guest exports `search` per its WIT world; set the
        // entry point explicitly so intent is visible in the registry.
        final long id = invokeRegistry.insert(
                new InvokeRegistry.InvokeSpec(entry.backendUrl(), args, "search"));
        return InvokeRegistry.iriFor(id);
    }

    private static String[] splitRegistryOpts(final FulltextRegistry.FulltextIndex entry) {
        String backendEndpoint = "http://localhost:9308";
        String indexName = entry.name();
        try {
            final JsonValue parsed = JSON.parseAny(entry.optsJson());
            if (parsed != null && parsed.isObject()) {
                final JsonObject obj = parsed.getAsObject();
                if (obj.hasKey("backend_endpoint") && obj.get("backend_endpoint").isString()) {
                    backendEndpoint = obj.get("backend_endpoint").getAsString().value();
                } else if (obj.hasKey("backend_url") && obj.get("backend_url").isString()) {
                    backendEndpoint = obj.get("backend_url").getAsString().value();
                }
                if (obj.hasKey("index") && obj.get("index").isString()) {
                    indexName = obj.get("index").getAsString().value();
                }
            }
        } catch (RuntimeException ignored) {
            // Fall back to defaults; matches Rust `unwrap_or(Value::Null)`.
        }
        return new String[] { backendEndpoint, indexName };
    }

    /**
     * Query-time opts JSON. Minimal on purpose: propagates the language
     * tag when present, {@code case_insensitive} when the wrapper implied
     * it, plus a generous limit and highlight=false.
     */
    private static String buildQueryOptsJson(final String lang, final CaseHint caseHint) {
        // Hand-built to match the Rust output's key ordering:
        //   {"limit":10000,"highlight":false[,"lang":"..."][,"case_insensitive":true]}
        final StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"limit\":10000");
        sb.append(",\"highlight\":false");
        if (lang != null) {
            sb.append(",\"lang\":\"").append(jsonEscape(lang)).append('"');
        }
        if (caseHint.isCi()) {
            sb.append(",\"case_insensitive\":true");
        }
        sb.append('}');
        return sb.toString();
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

    /**
     * SERVICE envelope BGP: {@code _:o wf:doc ?subj}. Binds the
     * outer-scope subject variable so the join restricts the pre-existing
     * BGP to the fulltext candidate set.
     */
    private Op buildEnvelope(final String subjectVarName) {
        // Fresh anonymous blank-node subject per envelope — mint a unique
        // name so nested envelopes don't collide.
        final Node oNode = NodeFactory.createBlankNode(
                "wf_ft_o_" + UUID.randomUUID().toString().replace("-", ""));
        final Node pNode = NodeFactory.createURI(WF_DOC);
        final Node subjNode = Var.alloc(subjectVarName);
        final BasicPattern bp = new BasicPattern();
        bp.add(Triple.create(oNode, pNode, subjNode));
        return new OpBGP(bp);
    }

    // Suppress unused-warning for import kept for API clarity.
    @SuppressWarnings("unused")
    private static void keepImports() {
        Object a = ExprFunction1.class;
        Object b = ExprFunction2.class;
    }
}
