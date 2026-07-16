package ai.tegmentum.jena.webfunctions.rewrite;

import org.apache.jena.graph.Node;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVisitorBase;
import org.apache.jena.sparql.algebra.OpWalker;
import org.apache.jena.sparql.algebra.op.OpService;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WfSageGraphRewrite} — cross-engine parity with
 * {@code oxigraph-wf/src/wf_sagegraph_rewrite.rs::rewrite_query_guest_dispatch}
 * and {@code qlever-wf-runtime/src/wf_sagegraph_rewrite.rs}.
 */
public class WfSageGraphRewriteTest {

    private static final String WASM_URL = "file:///opt/wf_sagegraph.wasm";

    // --- URL parser tests ------------------------------------------------

    @Test
    public void parsesNodeAndK() {
        final WfSageGraphRewrite.ParsedUrl p = WfSageGraphRewrite.parseUrl(
                "wf-sagegraph:people?node=http%3A%2F%2Fex%2Falice&k=2");
        assertThat(p).isNotNull();
        assertThat(p.name).isEqualTo("people");
        assertThat(p.nodeIri).isEqualTo("http://ex/alice");
        assertThat(p.k).isEqualTo(2);
        assertThat(p.model).isNull();
        assertThat(p.pool).isNull();
        // Wave-15 text-mode opts: absent unless the URL sugar sets them.
        assertThat(p.features).isNull();
        assertThat(p.textModel).isNull();
        assertThat(p.textPredicate).isNull();
    }

    // --- Wave-15 URL parser tests (text-mode opts, memo §06) ------------

    @Test
    public void parsesFeaturesTextOpt() {
        final WfSageGraphRewrite.ParsedUrl p = WfSageGraphRewrite.parseUrl(
                "wf-sagegraph:people-text?node=http%3A%2F%2Fex%2Falice&features=text");
        assertThat(p).isNotNull();
        assertThat(p.features).isEqualTo("text");
        assertThat(p.textModel).isNull();
        assertThat(p.textPredicate).isNull();
    }

    @Test
    public void parsesTextModelKebabAndSnake() {
        // Kebab-case matches the guest's WIT record field name;
        // snake_case is the URL-param convention some engines emit.
        // Both must be accepted.
        final WfSageGraphRewrite.ParsedUrl kebab = WfSageGraphRewrite.parseUrl(
                "wf-sagegraph:people-text?node=http%3A%2F%2Fex%2Fa"
                        + "&features=text&text-model=all-MiniLM-L6-v2");
        assertThat(kebab).isNotNull();
        assertThat(kebab.textModel).isEqualTo("all-MiniLM-L6-v2");

        final WfSageGraphRewrite.ParsedUrl snake = WfSageGraphRewrite.parseUrl(
                "wf-sagegraph:people-text?node=http%3A%2F%2Fex%2Fa"
                        + "&features=text&text_model=all-MiniLM-L6-v2");
        assertThat(snake).isNotNull();
        assertThat(snake.textModel).isEqualTo("all-MiniLM-L6-v2");
    }

    @Test
    public void parsesTextPredicateKebabAndSnakeUrldecoded() {
        final WfSageGraphRewrite.ParsedUrl kebab = WfSageGraphRewrite.parseUrl(
                "wf-sagegraph:people-text?node=http%3A%2F%2Fex%2Fa"
                        + "&features=text&text-predicate=http%3A%2F%2Fexample.com%2Fname");
        assertThat(kebab).isNotNull();
        assertThat(kebab.textPredicate).isEqualTo("http://example.com/name");

        final WfSageGraphRewrite.ParsedUrl snake = WfSageGraphRewrite.parseUrl(
                "wf-sagegraph:people-text?node=http%3A%2F%2Fex%2Fa"
                        + "&features=text&text_predicate=http%3A%2F%2Fexample.com%2Fname");
        assertThat(snake).isNotNull();
        assertThat(snake.textPredicate).isEqualTo("http://example.com/name");
    }

    @Test
    public void parsesModelAndPoolOpts() {
        final WfSageGraphRewrite.ParsedUrl p = WfSageGraphRewrite.parseUrl(
                "wf-sagegraph:people?node=http%3A%2F%2Fex%2Falice&k=2"
                        + "&model=file%3A%2F%2F%2Fopt%2Fsage.onnx&pool=max");
        assertThat(p).isNotNull();
        assertThat(p.model).isEqualTo("file:///opt/sage.onnx");
        assertThat(p.pool).isEqualTo("max");
    }

    @Test
    public void defaultsKAbsent() {
        final WfSageGraphRewrite.ParsedUrl p = WfSageGraphRewrite.parseUrl(
                "wf-sagegraph:people?node=http%3A%2F%2Fex%2Falice");
        assertThat(p).isNotNull();
        assertThat(p.k).isNull();
    }

    @Test
    public void rejectsMissingNode() {
        assertThat(WfSageGraphRewrite.parseUrl("wf-sagegraph:people?k=2")).isNull();
    }

    @Test
    public void rejectsBareScheme() {
        assertThat(WfSageGraphRewrite.parseUrl("wf-sagegraph:")).isNull();
    }

    // --- Rewrite tests --------------------------------------------------

    private static Op algebraOf(final String sparql) {
        final Query q = QueryFactory.create(sparql);
        return Algebra.compile(q);
    }

    private static List<OpService> collectServices(final Op op) {
        final List<OpService> out = new ArrayList<>();
        OpWalker.walk(op, new OpVisitorBase() {
            @Override
            public void visit(final OpService svc) {
                out.add(svc);
            }
        });
        return out;
    }

    private static Optional<OpService> firstWfInvokeService(final Op op) {
        for (OpService s : collectServices(op)) {
            final Node ref = s.getService();
            if (ref != null && ref.isURI()
                    && ref.getURI().startsWith(InvokeRegistry.WF_INVOKE_SCHEME)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    private static Optional<OpService> firstWfSagegraphService(final Op op) {
        for (OpService s : collectServices(op)) {
            final Node ref = s.getService();
            if (ref != null && ref.isURI()
                    && ref.getURI().startsWith(WfSageGraphRewrite.WF_SAGEGRAPH_SCHEME)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    @Test
    public void foldsServiceIntoWfInvoke() {
        final InvokeRegistry invokes = new InvokeRegistry();
        final String sparql = ""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?node ?embedding WHERE {\n"
                + "  SERVICE <wf-sagegraph:people?node=http%3A%2F%2Fex%2Falice&k=2> {\n"
                + "    ?_ wf:node ?node ; wf:embedding ?embedding\n"
                + "  }\n"
                + "}";
        final Op in = algebraOf(sparql);
        final Op out = WfSageGraphRewrite.rewrite(in, invokes, WASM_URL);

        assertThat(firstWfInvokeService(out)).isPresent();
        assertThat(firstWfSagegraphService(out)).isEmpty();
        assertThat(invokes.size()).isEqualTo(1);

        final InvokeRegistry.InvokeSpec spec = invokes.get(0L).orElseThrow();
        assertThat(spec.wasmUrl).isEqualTo(WASM_URL);
        assertThat(spec.entryPoint).isEqualTo("embed");
        assertThat(spec.args).hasSize(4);
        assertThat(spec.args.get(0).getLiteralLexicalForm()).isEqualTo("http://ex/alice");
        assertThat(spec.args.get(1).getLiteralLexicalForm())
                .isEqualTo("wf-sagegraph:stubbed-model");
        assertThat(spec.args.get(2).getLiteralLexicalForm()).isEqualTo("2");
        final String opts = spec.args.get(3).getLiteralLexicalForm();
        assertThat(opts).contains("\"dimensions\":8");
        assertThat(opts).contains("\"pool\":\"mean\"");

        // Projection captured from the SERVICE body so the dispatcher
        // can rename guest columns onto outer-query variables.
        assertThat(spec.projection).containsEntry("node", "node");
        assertThat(spec.projection).containsEntry("embedding", "embedding");
    }

    @Test
    public void urlModelAndPoolLandInArgs() {
        final InvokeRegistry invokes = new InvokeRegistry();
        final String sparql = ""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?embedding WHERE {\n"
                + "  SERVICE <wf-sagegraph:people?node=http%3A%2F%2Fex%2Falice"
                + "&k=1&model=file%3A%2F%2F%2Fopt%2Fsage.onnx&pool=sum> {\n"
                + "    ?_ wf:embedding ?embedding\n"
                + "  }\n"
                + "}";
        final Op in = algebraOf(sparql);
        WfSageGraphRewrite.rewrite(in, invokes, WASM_URL);

        assertThat(invokes.size()).isEqualTo(1);
        final InvokeRegistry.InvokeSpec spec = invokes.get(0L).orElseThrow();
        assertThat(spec.args.get(1).getLiteralLexicalForm())
                .isEqualTo("file:///opt/sage.onnx");
        assertThat(spec.args.get(3).getLiteralLexicalForm()).contains("\"pool\":\"sum\"");
    }

    @Test
    public void emptyWasmUrlShortCircuits() {
        final InvokeRegistry invokes = new InvokeRegistry();
        final String sparql = ""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?embedding WHERE {\n"
                + "  SERVICE <wf-sagegraph:people?node=http%3A%2F%2Fex%2Falice&k=1> {\n"
                + "    ?_ wf:embedding ?embedding\n"
                + "  }\n"
                + "}";
        final Op in = algebraOf(sparql);
        final Op out = WfSageGraphRewrite.rewrite(in, invokes, "");
        assertThat(out).isSameAs(in);
        assertThat(invokes.size()).isEqualTo(0);
    }

    @Test
    public void nullInvokesShortCircuits() {
        final String sparql = ""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?embedding WHERE {\n"
                + "  SERVICE <wf-sagegraph:people?node=http%3A%2F%2Fex%2Falice&k=1> {\n"
                + "    ?_ wf:embedding ?embedding\n"
                + "  }\n"
                + "}";
        final Op in = algebraOf(sparql);
        final Op out = WfSageGraphRewrite.rewrite(in, null, WASM_URL);
        assertThat(out).isSameAs(in);
    }

    @Test
    public void bodyWithoutEmbeddingLeavesServiceAlone() {
        // Only ?_ wf:node ?node — no wf:embedding projection means the
        // dispatcher has nothing to bind, so leave the sugar alone.
        final InvokeRegistry invokes = new InvokeRegistry();
        final String sparql = ""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?node WHERE {\n"
                + "  SERVICE <wf-sagegraph:people?node=http%3A%2F%2Fex%2Fa&k=1> {\n"
                + "    ?_ wf:node ?node\n"
                + "  }\n"
                + "}";
        final Op in = algebraOf(sparql);
        final Op out = WfSageGraphRewrite.rewrite(in, invokes, WASM_URL);
        assertThat(invokes.size()).isEqualTo(0);
        assertThat(firstWfSagegraphService(out)).isPresent();
    }

    @Test
    public void malformedUrlLeavesServiceAlone() {
        // Missing ?node= — parseUrl returns null, pass leaves alone.
        final InvokeRegistry invokes = new InvokeRegistry();
        final String sparql = ""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?embedding WHERE {\n"
                + "  SERVICE <wf-sagegraph:people?k=1> {\n"
                + "    ?_ wf:embedding ?embedding\n"
                + "  }\n"
                + "}";
        final Op in = algebraOf(sparql);
        final Op out = WfSageGraphRewrite.rewrite(in, invokes, WASM_URL);
        assertThat(invokes.size()).isEqualTo(0);
        assertThat(firstWfSagegraphService(out)).isPresent();
    }

    // --- Wave-15 rewrite tests (text-mode opts flow to opts_json) -------

    @Test
    public void featuresTextLandsInOptsJson() {
        final InvokeRegistry invokes = new InvokeRegistry();
        final String sparql = ""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?embedding WHERE {\n"
                + "  SERVICE <wf-sagegraph:people?node=http%3A%2F%2Fex%2Fa&k=1&features=text> {\n"
                + "    ?_ wf:embedding ?embedding\n"
                + "  }\n"
                + "}";
        WfSageGraphRewrite.rewrite(algebraOf(sparql), invokes, WASM_URL);
        assertThat(invokes.size()).isEqualTo(1);
        final String opts = invokes.get(0L).orElseThrow()
                .args.get(3).getLiteralLexicalForm();
        assertThat(opts).contains("\"features\":\"text\"");
        assertThat(opts).doesNotContain("\"text-model\"");
        assertThat(opts).doesNotContain("\"text-predicate\"");
    }

    @Test
    public void textModelSnakeCaseKebabizedInOptsJson() {
        final InvokeRegistry invokes = new InvokeRegistry();
        // URL carries snake-case `text_model=` — opts_json must emit
        // kebab-case `text-model` to match the guest's WIT record.
        final String sparql = ""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?embedding WHERE {\n"
                + "  SERVICE <wf-sagegraph:people?node=http%3A%2F%2Fex%2Fa"
                + "&k=1&features=text&text_model=all-MiniLM-L6-v2> {\n"
                + "    ?_ wf:embedding ?embedding\n"
                + "  }\n"
                + "}";
        WfSageGraphRewrite.rewrite(algebraOf(sparql), invokes, WASM_URL);
        assertThat(invokes.size()).isEqualTo(1);
        final String opts = invokes.get(0L).orElseThrow()
                .args.get(3).getLiteralLexicalForm();
        assertThat(opts).contains("\"features\":\"text\"");
        assertThat(opts).contains("\"text-model\":\"all-MiniLM-L6-v2\"");
    }

    @Test
    public void absencePreservedWhenUrlOmitsTextOpts() {
        // Structural (default) mode — no text opts on the URL, so the
        // opts_json wire shape stays byte-stable for the pinned
        // `sagegraph_degree_features` case.
        final InvokeRegistry invokes = new InvokeRegistry();
        final String sparql = ""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?embedding WHERE {\n"
                + "  SERVICE <wf-sagegraph:people?node=http%3A%2F%2Fex%2Fa&k=1> {\n"
                + "    ?_ wf:embedding ?embedding\n"
                + "  }\n"
                + "}";
        WfSageGraphRewrite.rewrite(algebraOf(sparql), invokes, WASM_URL);
        assertThat(invokes.size()).isEqualTo(1);
        final String opts = invokes.get(0L).orElseThrow()
                .args.get(3).getLiteralLexicalForm();
        assertThat(opts).doesNotContain("\"features\"");
        assertThat(opts).doesNotContain("\"text-model\"");
        assertThat(opts).doesNotContain("\"text-predicate\"");
    }

    @Test
    public void nonSagegraphServiceLeftAlone() {
        // A wf-search: SERVICE — different scheme, this pass ignores it.
        final InvokeRegistry invokes = new InvokeRegistry();
        final String sparql = ""
                + "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n"
                + "SELECT ?doc WHERE {\n"
                + "  SERVICE <wf-search:manuals?query=hi> {\n"
                + "    ?_ wf:embedding ?doc\n"
                + "  }\n"
                + "}";
        final Op in = algebraOf(sparql);
        final Op out = WfSageGraphRewrite.rewrite(in, invokes, WASM_URL);
        assertThat(invokes.size()).isEqualTo(0);
        assertThat(firstWfInvokeService(out)).isEmpty();
    }
}
