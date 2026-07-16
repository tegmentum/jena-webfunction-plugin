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
