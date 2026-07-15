package ai.tegmentum.jena.webfunctions.rewrite;

import ai.tegmentum.jena.webfunctions.rewrite.DocumentRegistry.DocumentIndex;
import ai.tegmentum.jena.webfunctions.rewrite.DocumentRegistry.DocumentMode;

import org.apache.jena.graph.Node;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVisitorBase;
import org.apache.jena.sparql.algebra.OpWalker;
import org.apache.jena.sparql.algebra.op.OpService;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WfSearchRewrite}. Covers both the URL parser
 * (design memo &sect;05 grammar) and the OpService substitution.
 * Sibling of {@code oxigraph-wf/src/wf_search_rewrite.rs::tests}.
 */
public class TestWfSearchRewrite {

    // ---------------------------------------------------------------------
    // Registry factories
    // ---------------------------------------------------------------------

    private static DocumentRegistry manualsRegistry() {
        final DocumentIndex ix = new DocumentIndex(
                "manuals",
                DocumentMode.MANAGED,
                "file:///opt/wf_document.wasm",
                "http://localhost:9308",
                "http://localhost:8080",
                "manuals",
                "docs",
                "manuals",
                "{}",
                OptionalInt.of(300),
                "all");
        return DocumentRegistry.of(List.of(ix));
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static Op parseAlgebra(final String sparql) {
        return Algebra.compile(QueryFactory.create(sparql));
    }

    private static List<OpService> collectServices(final Op op) {
        final List<OpService> out = new ArrayList<>();
        OpWalker.walk(op, new OpVisitorBase() {
            @Override public void visit(final OpService s) { out.add(s); }
        });
        return out;
    }

    private static boolean hasWfInvokeService(final Op op) {
        for (OpService s : collectServices(op)) {
            final Node ref = s.getService();
            if (ref != null && ref.isURI()
                    && ref.getURI().startsWith(InvokeRegistry.WF_INVOKE_SCHEME)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasWfSearchService(final Op op) {
        for (OpService s : collectServices(op)) {
            final Node ref = s.getService();
            if (ref != null && ref.isURI()
                    && ref.getURI().startsWith(WfSearchRewrite.WF_SEARCH_SCHEME)) {
                return true;
            }
        }
        return false;
    }

    private static InvokeRegistry.InvokeSpec takeFirstInvoke(final InvokeRegistry inv) {
        final InvokeRegistry.InvokeSpec s = inv.take(0L).orElse(null);
        assertThat(s).as("expected an InvokeSpec at id 0").isNotNull();
        return s;
    }

    private static String optsJsonArg(final InvokeRegistry.InvokeSpec spec) {
        // args = [searchBackend, storageBackend, index, query, optsJson]
        // per the wf_document `search` export WIT signature. `limit`
        // lives inside optsJson (search-opts.limit: option<u32>).
        return spec.args.get(4).getLiteralLexicalForm();
    }

    // ---------------------------------------------------------------------
    // URL parser tests
    // ---------------------------------------------------------------------

    @Test
    public void parsesBareName() {
        final WfSearchRewrite.ParsedUrl p = WfSearchRewrite.parseUrl("wf-search:manuals");
        assertThat(p).isNotNull();
        assertThat(p.name).isEqualTo("manuals");
        assertThat(p.atTime).isNull();
        assertThat(p.atRev).isNull();
        assertThat(p.opts).isEmpty();
    }

    @Test
    public void parsesTimeIso() {
        final WfSearchRewrite.ParsedUrl p = WfSearchRewrite.parseUrl(
                "wf-search:manuals@2026-01-01T00:00:00Z");
        assertThat(p).isNotNull();
        assertThat(p.name).isEqualTo("manuals");
        assertThat(p.atTime).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(p.atRev).isNull();
    }

    @Test
    public void parsesTimeRev() {
        final WfSearchRewrite.ParsedUrl p = WfSearchRewrite.parseUrl("wf-search:manuals@rev17");
        assertThat(p).isNotNull();
        assertThat(p.name).isEqualTo("manuals");
        assertThat(p.atTime).isNull();
        assertThat(p.atRev).isEqualTo(17L);
    }

    @Test
    public void parsesOpts() {
        final WfSearchRewrite.ParsedUrl p = WfSearchRewrite.parseUrl(
                "wf-search:manuals?highlight=true&lang=en");
        assertThat(p).isNotNull();
        assertThat(p.name).isEqualTo("manuals");
        assertThat(p.atTime).isNull();
        assertThat(p.atRev).isNull();
        assertThat(p.opts).containsEntry("highlight", "true");
        assertThat(p.opts).containsEntry("lang", "en");
    }

    @Test
    public void parsesTimeAndOpts() {
        final WfSearchRewrite.ParsedUrl p = WfSearchRewrite.parseUrl(
                "wf-search:manuals@2026-01-01?highlight=true&include_body=true");
        assertThat(p).isNotNull();
        assertThat(p.name).isEqualTo("manuals");
        assertThat(p.atTime).isEqualTo("2026-01-01");
        assertThat(p.opts).containsEntry("highlight", "true");
        assertThat(p.opts).containsEntry("include_body", "true");
    }

    @Test
    public void rejectsMissingName() {
        // Bare scheme, nothing after the colon.
        assertThat(WfSearchRewrite.parseUrl("wf-search:")).isNull();
        // Time-spec but empty name.
        assertThat(WfSearchRewrite.parseUrl("wf-search:@rev1")).isNull();
        // Opts but empty name.
        assertThat(WfSearchRewrite.parseUrl("wf-search:?highlight=true")).isNull();
    }

    @Test
    public void parsesRangeOpts() {
        final WfSearchRewrite.ParsedUrl p = WfSearchRewrite.parseUrl(
                "wf-search:manuals?after=2026-01-01&before=2026-06-01");
        assertThat(p).isNotNull();
        assertThat(p.name).isEqualTo("manuals");
        assertThat(p.atTime).isNull();
        assertThat(p.atRev).isNull();
        assertThat(p.opts).containsEntry("after", "2026-01-01");
        assertThat(p.opts).containsEntry("before", "2026-06-01");
    }

    @Test
    public void rejectsAtTimeWithRange() {
        // @time-spec and ?after= / ?before= are mutually exclusive at
        // parse time (v1.3 range-queries invariant).
        assertThat(WfSearchRewrite.parseUrl(
                "wf-search:manuals@2026-01-01?after=2025-01-01")).isNull();
        assertThat(WfSearchRewrite.parseUrl(
                "wf-search:manuals@rev17?before=2026-06-01")).isNull();
        assertThat(WfSearchRewrite.parseUrl(
                "wf-search:manuals@2026-01-01?after=2025-01-01&before=2026-06-01"))
                .isNull();
    }

    // ---------------------------------------------------------------------
    // OpService substitution tests
    // ---------------------------------------------------------------------

    @Test
    public void rewritesBareService() {
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?doc .\n"
                + "  }\n"
                + "}");
        final Op out = WfSearchRewrite.rewrite(input, reg, inv);
        assertThat(hasWfInvokeService(out)).isTrue();
        assertThat(hasWfSearchService(out)).isFalse();

        final InvokeRegistry.InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(spec.entryPoint).isEqualTo("search");
        assertThat(spec.wasmUrl).isEqualTo("file:///opt/wf_document.wasm");
        assertThat(spec.args).hasSize(5);
        assertThat(spec.args.get(0).getLiteralLexicalForm()).isEqualTo("http://localhost:9308");
        assertThat(spec.args.get(1).getLiteralLexicalForm()).isEqualTo("http://localhost:8080");
        assertThat(spec.args.get(2).getLiteralLexicalForm()).isEqualTo("manuals");
        assertThat(spec.args.get(3).getLiteralLexicalForm()).isEqualTo("waterproof");
        // limit lives inside opts_json per the WIT record shape.
        assertThat(optsJsonArg(spec)).contains("\"limit\":20");
        assertThat(optsJsonArg(spec)).doesNotContain("at_time");
        assertThat(optsJsonArg(spec)).doesNotContain("at_rev");
    }

    @Test
    public void rewritesWithTime() {
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals@2026-01-01T00:00:00Z> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?doc .\n"
                + "  }\n"
                + "}");
        final Op out = WfSearchRewrite.rewrite(input, reg, inv);
        assertThat(hasWfInvokeService(out)).isTrue();

        final InvokeRegistry.InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(optsJsonArg(spec)).contains("\"at_time\":\"2026-01-01T00:00:00Z\"");
        assertThat(optsJsonArg(spec)).doesNotContain("at_rev");
    }

    @Test
    public void rewritesWithRev() {
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals@rev17> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?doc .\n"
                + "  }\n"
                + "}");
        final Op out = WfSearchRewrite.rewrite(input, reg, inv);
        assertThat(hasWfInvokeService(out)).isTrue();

        final InvokeRegistry.InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(optsJsonArg(spec)).contains("\"at_rev\":17");
        assertThat(optsJsonArg(spec)).doesNotContain("at_time");
    }

    @Test
    public void rewritesWithRangeService() {
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals?after=2026-01-01&before=2026-06-01> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?doc .\n"
                + "  }\n"
                + "}");
        final Op out = WfSearchRewrite.rewrite(input, reg, inv);
        assertThat(hasWfInvokeService(out)).isTrue();

        final InvokeRegistry.InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(optsJsonArg(spec)).contains("\"after\":\"2026-01-01\"");
        assertThat(optsJsonArg(spec)).contains("\"before\":\"2026-06-01\"");
        assertThat(optsJsonArg(spec)).doesNotContain("at_time");
        assertThat(optsJsonArg(spec)).doesNotContain("at_rev");
    }

    @Test
    public void unregisteredNameSkips() {
        // Registry knows only "manuals"; query invokes "spec-sheets".
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:spec-sheets> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?doc .\n"
                + "  }\n"
                + "}");
        final Op out = WfSearchRewrite.rewrite(input, reg, inv);
        assertThat(hasWfInvokeService(out)).isFalse();
        assertThat(hasWfSearchService(out)).isTrue();
        assertThat(inv.size()).isZero();
    }

    @Test
    public void missingWfQuerySkips() {
        // Registered name, but body has no wf:query triple.
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals> {\n"
                + "    ?_ wf:doc ?doc .\n"
                + "  }\n"
                + "}");
        final Op out = WfSearchRewrite.rewrite(input, reg, inv);
        assertThat(hasWfInvokeService(out)).isFalse();
        assertThat(hasWfSearchService(out)).isTrue();
        assertThat(inv.size()).isZero();
    }

    @Test
    public void urlQueryParamFoldsWithoutBodyTriple() {
        // The URL-parameter sugar (`?query=<term>`) supplies the search
        // string when the SERVICE body has no `wf:query "…"` triple.
        // Shape used by `federation_heterogeneous.toml`: the wf-search
        // side of a heterogeneous federation query.
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals?query=waterproof> {\n"
                + "    ?_ wf:doc ?doc .\n"
                + "  }\n"
                + "}");
        final Op out = WfSearchRewrite.rewrite(input, reg, inv);
        assertThat(hasWfInvokeService(out))
                .as("?query=<term> URL param must fold without a wf:query body triple")
                .isTrue();
        assertThat(hasWfSearchService(out)).isFalse();

        final InvokeRegistry.InvokeSpec spec = takeFirstInvoke(inv);
        // Positional arg 3 is the search string — must be the URL opt.
        assertThat(spec.args.get(3).getLiteralLexicalForm()).isEqualTo("waterproof");
        // `query` is NOT propagated into opts_json — the guest reads
        // the string from the positional arg, not from opts.
        assertThat(optsJsonArg(spec)).doesNotContain("\"query\"");
    }

    @Test
    public void bodyWfQueryWinsOverUrlQueryParam() {
        // When both a body `wf:query "…"` triple AND a URL `?query=<term>`
        // opt are present, the body-triple form wins — it is closer to
        // the caller's intent than a URL string decorated by federation
        // config.
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals?query=urlterm> {\n"
                + "    ?_ wf:query \"bodyterm\" ; wf:doc ?doc .\n"
                + "  }\n"
                + "}");
        final Op out = WfSearchRewrite.rewrite(input, reg, inv);
        assertThat(hasWfInvokeService(out)).isTrue();

        final InvokeRegistry.InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(spec.args.get(3).getLiteralLexicalForm()).isEqualTo("bodyterm");
    }

    // ---------------------------------------------------------------------
    // Memo §10 smart-set: wf:snippet → highlight=true
    // ---------------------------------------------------------------------

    @Test
    public void smartSetsHighlightWhenBodyProjectsSnippet() {
        // SERVICE body binds `wf:snippet ?snippet`; URL doesn't touch
        // highlight → memo §10 smart-set kicks in and the emitted opts
        // JSON carries `"highlight":true`.
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc ?snippet WHERE {\n"
                + "  SERVICE <wf-search:manuals> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?doc ; wf:snippet ?snippet .\n"
                + "  }\n"
                + "}");
        final Op out = WfSearchRewrite.rewrite(input, reg, inv);
        assertThat(hasWfInvokeService(out)).isTrue();

        final InvokeRegistry.InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(optsJsonArg(spec))
                .as("wf:snippet in body must smart-set highlight=true")
                .contains("\"highlight\":true");
    }

    @Test
    public void noSmartSetWhenBodyOmitsSnippet() {
        // No wf:snippet in the body → substrate emits the WIT-required
        // `highlight:false` default. The pre-v1.3 behavior was to omit
        // the key entirely and let the guest pick a default; the WIT
        // now declares `highlight: bool` as non-option, so the
        // substrate must always emit it.
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?doc .\n"
                + "  }\n"
                + "}");
        final Op out = WfSearchRewrite.rewrite(input, reg, inv);
        assertThat(hasWfInvokeService(out)).isTrue();

        final InvokeRegistry.InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(optsJsonArg(spec))
                .as("no wf:snippet must emit WIT-required highlight=false default")
                .contains("\"highlight\":false")
                .doesNotContain("\"highlight\":true");
    }

    @Test
    public void optsJsonIncludesRequiredWitFields() {
        // The wf_document WIT `search-opts` record declares three
        // non-optional bool/list fields — `fields: list<string>`,
        // `highlight: bool`, and `include-body: bool` (memo §04,
        // `wf-document.wit` v1.3). The substrate coercer errors out on
        // any missing required field before the dispatch reaches the
        // guest ("arg 4 of `search`: record missing required field
        // `<name>`"), so every wf_document opts_json emission must
        // include all three. Parallel guard to the wf_fulltext test of
        // the same name.
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?doc .\n"
                + "  }\n"
                + "}");
        final Op out = WfSearchRewrite.rewrite(input, reg, inv);
        assertThat(hasWfInvokeService(out)).isTrue();

        final InvokeRegistry.InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(optsJsonArg(spec))
                .as("opts_json must include the WIT-required `fields`, `highlight`, and `include-body` defaults")
                .contains("\"fields\":[]")
                .contains("\"highlight\":false")
                .contains("\"include-body\":false");
    }

    @Test
    public void optsJsonSnakeIncludeBodyUrlBecomesKebabWitKey() {
        // URL query params are conventionally snake_case
        // (`?include_body=true`) but the WIT `search-opts` record
        // declares fields kebab-case (`include-body`). The emitter
        // must translate on the way in — otherwise the marshaller
        // fails with "record missing required field `include-body`".
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals?include_body=true> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?doc .\n"
                + "  }\n"
                + "}");
        final Op out = WfSearchRewrite.rewrite(input, reg, inv);
        assertThat(hasWfInvokeService(out)).isTrue();

        final InvokeRegistry.InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(optsJsonArg(spec))
                .as("URL ?include_body=true must emit kebab-case JSON key")
                .contains("\"include-body\":true")
                .doesNotContain("\"include_body\":");
    }

    @Test
    public void optsJsonUrlFieldsPlaceholder() {
        // Guard on existing behavior: `?fields=` isn't currently a
        // whitelisted URL opt, so the emitted opts_json falls back to
        // the WIT-required default `fields:[]`. Documents parity with
        // the sibling Oxigraph / QLever / RDF4J tests.
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?doc .\n"
                + "  }\n"
                + "}");
        final Op out = WfSearchRewrite.rewrite(input, reg, inv);
        final InvokeRegistry.InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(optsJsonArg(spec)).contains("\"fields\":[]");
    }

    @Test
    public void urlHighlightFalseWinsOverSnippetSmartSet() {
        // URL explicitly says highlight=false; body still projects
        // wf:snippet. URL wins per memo §10.
        final DocumentRegistry reg = manualsRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc ?snippet WHERE {\n"
                + "  SERVICE <wf-search:manuals?highlight=false> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?doc ; wf:snippet ?snippet .\n"
                + "  }\n"
                + "}");
        final Op out = WfSearchRewrite.rewrite(input, reg, inv);
        assertThat(hasWfInvokeService(out)).isTrue();

        final InvokeRegistry.InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(optsJsonArg(spec))
                .as("URL ?highlight=false must win over smart-set")
                .contains("\"highlight\":false")
                .doesNotContain("\"highlight\":true");
    }

    @Test
    public void emptyRegistryNoop() {
        final DocumentRegistry reg = DocumentRegistry.empty();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?doc .\n"
                + "  }\n"
                + "}");
        final Op out = WfSearchRewrite.rewrite(input, reg, inv);
        assertThat(hasWfInvokeService(out)).isFalse();
        // Original SERVICE URI is preserved untouched.
        assertThat(hasWfSearchService(out)).isTrue();
        assertThat(inv.size()).isZero();
    }

    // ---------------------------------------------------------------------
    // FederationRegistry fallback (wave-3 federation_heterogeneous)
    // ---------------------------------------------------------------------

    /**
     * Federation registry with a single wf-search source named
     * `manuals-search` and a self-referential endpoint — mirrors the
     * shape `federation_heterogeneous.toml` seeds via
     * `--federation-config` (no matching fulltext / document entry).
     */
    private static FederationRegistry manualsFederationRegistry() {
        return FederationRegistry.of(List.of(new FederationRegistry.FederationSource(
                "manuals-search",
                FederationRegistry.SourceType.WF_SEARCH,
                "wf-search:manuals-search",
                List.of(),
                OptionalInt.empty())));
    }

    /**
     * Federation registry whose wf-search source declares a real HTTP
     * endpoint — exercises the passthrough branch of
     * {@code federationBackendEndpoint}.
     */
    private static FederationRegistry manualsFederationRegistryHttp() {
        return FederationRegistry.of(List.of(new FederationRegistry.FederationSource(
                "manuals-search",
                FederationRegistry.SourceType.WF_SEARCH,
                "http://manticore.internal:9309",
                List.of(),
                OptionalInt.empty())));
    }

    @Test
    public void foldsViaFederationRegistryWhenOthersMiss() {
        // Neither DocumentRegistry nor FulltextRegistry knows the name;
        // only the FederationRegistry has a wf-search entry for it.
        // The pass must still fold using synthesized wf_fulltext
        // dispatch (env-var-derived wasm URL + Manticore default).
        final DocumentRegistry docReg = DocumentRegistry.empty();
        final FulltextRegistry ftReg = FulltextRegistry.empty();
        final FederationRegistry fedReg = manualsFederationRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?m ?snippet WHERE {\n"
                + "  SERVICE <wf-search:manuals-search> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?m ; wf:snippet ?snippet .\n"
                + "  }\n"
                + "}");
        final Op out = WfSearchRewrite.rewrite(input, docReg, ftReg, fedReg, inv);
        assertThat(hasWfInvokeService(out))
                .as("federation fallback must fold the SERVICE")
                .isTrue();
        assertThat(hasWfSearchService(out)).isFalse();

        final InvokeRegistry.InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(spec.entryPoint).isEqualTo("search");
        // wf_fulltext ABI: [backend_endpoint, index, query, opts_json]
        assertThat(spec.args).hasSize(4);
        assertThat(spec.args.get(1).getLiteralLexicalForm())
                .as("index should be entry name")
                .isEqualTo("manuals-search");
        assertThat(spec.args.get(2).getLiteralLexicalForm()).isEqualTo("waterproof");
        assertThat(spec.args.get(3).getLiteralLexicalForm())
                .as("wf:snippet in body must smart-set highlight=true")
                .contains("\"highlight\":true");
    }

    @Test
    public void foldsViaFederationRegistryHonoursHttpEndpoint() {
        // A federation wf-search entry with a real HTTP endpoint should
        // pass through as the wf_fulltext `backend_endpoint` arg
        // verbatim (not fall through to the Manticore default).
        final FederationRegistry fedReg = manualsFederationRegistryHttp();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?m WHERE {\n"
                + "  SERVICE <wf-search:manuals-search> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?m .\n"
                + "  }\n"
                + "}");
        final Op out = WfSearchRewrite.rewrite(
                input, DocumentRegistry.empty(), FulltextRegistry.empty(), fedReg, inv);
        assertThat(hasWfInvokeService(out)).isTrue();

        final InvokeRegistry.InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(spec.args.get(0).getLiteralLexicalForm())
                .as("HTTP-shaped federation endpoint must pass through verbatim")
                .isEqualTo("http://manticore.internal:9309");
    }

    @Test
    public void documentRegistryWinsOverFederation() {
        // Precedence is Document > Fulltext > Federation. When the
        // document registry knows the name, that path wins even if the
        // federation registry also names it.
        final DocumentRegistry docReg = manualsRegistry();
        final FederationRegistry fedReg = FederationRegistry.of(List.of(
                new FederationRegistry.FederationSource(
                        "manuals",
                        FederationRegistry.SourceType.WF_SEARCH,
                        "http://manticore.internal:9309",
                        List.of(),
                        OptionalInt.empty())));
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?doc .\n"
                + "  }\n"
                + "}");
        final Op out = WfSearchRewrite.rewrite(
                input, docReg, FulltextRegistry.empty(), fedReg, inv);
        assertThat(hasWfInvokeService(out)).isTrue();

        final InvokeRegistry.InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(spec.wasmUrl)
                .as("DocumentRegistry entry wins over the federation fallback")
                .isEqualTo("file:///opt/wf_document.wasm");
        assertThat(spec.args)
                .as("wf_document ABI expected, not wf_fulltext")
                .hasSize(5);
    }

    @Test
    public void federationWrongTypeIsSkipped() {
        // A federation entry with a non-wf-search type must not be
        // treated as a search source. The SERVICE is left alone.
        final FederationRegistry fedReg = FederationRegistry.of(List.of(
                new FederationRegistry.FederationSource(
                        "manuals-search",
                        FederationRegistry.SourceType.WF_FETCH,
                        "wf-fetch:manuals-search",
                        List.of(),
                        OptionalInt.empty())));
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?m WHERE {\n"
                + "  SERVICE <wf-search:manuals-search> {\n"
                + "    ?_ wf:query \"waterproof\" ; wf:doc ?m .\n"
                + "  }\n"
                + "}");
        final Op out = WfSearchRewrite.rewrite(
                input, DocumentRegistry.empty(), FulltextRegistry.empty(), fedReg, inv);
        assertThat(hasWfInvokeService(out))
                .as("wf-fetch federation source must not fold as wf-search")
                .isFalse();
        assertThat(hasWfSearchService(out)).isTrue();
    }
}
