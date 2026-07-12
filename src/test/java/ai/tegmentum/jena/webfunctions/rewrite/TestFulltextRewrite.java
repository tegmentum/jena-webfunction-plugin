package ai.tegmentum.jena.webfunctions.rewrite;

import org.apache.jena.graph.Node;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVisitorBase;
import org.apache.jena.sparql.algebra.OpWalker;
import org.apache.jena.sparql.algebra.op.OpFilter;
import org.apache.jena.sparql.algebra.op.OpService;
import org.apache.jena.sparql.expr.E_StrContains;
import org.apache.jena.sparql.expr.Expr;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Java port of the fulltext-rewrite unit tests. Mirrors
 * {@code oxigraph-wf/src/fulltext_rewrite.rs::tests} and the RDF4J
 * port's {@code TestFulltextRewrite}. Every fold-safe / fold-unsafe
 * shape from memo &sect;06 has a matching Rust test; each one is ported
 * verbatim below.
 */
public class TestFulltextRewrite {

    // ---------------------------------------------------------------------
    // Registry factories
    // ---------------------------------------------------------------------

    private static FulltextRegistry productsRegistryWord() {
        final FulltextRegistry.FulltextIndex ix = new FulltextRegistry.FulltextIndex(
                "products",
                FulltextRegistry.FulltextMode.LITERAL_INDEX,
                "file:///opt/wf_fulltext.wasm",
                List.of("http://ex/label"),
                "{\"index\":\"products\",\"backend_endpoint\":\"http://localhost:9308\"}",
                List.of("en"),
                OptionalInt.empty());
        return FulltextRegistry.of(List.of(ix));
    }

    private static FulltextRegistry documentCorpusRegistry() {
        final FulltextRegistry.FulltextIndex ix = new FulltextRegistry.FulltextIndex(
                "manuals",
                FulltextRegistry.FulltextMode.DOCUMENT_CORPUS,
                "file:///opt/wf_fulltext.wasm",
                List.of(),
                "{\"index\":\"manuals\"}",
                List.of(),
                OptionalInt.empty());
        return FulltextRegistry.of(List.of(ix));
    }

    // ---------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------

    private static Op parseAlgebra(final String sparql) {
        return Algebra.compile(QueryFactory.create(sparql));
    }

    private static boolean hasWfInvokeService(final Op op) {
        final boolean[] hit = new boolean[1];
        OpWalker.walk(op, new OpVisitorBase() {
            @Override
            public void visit(final OpService s) {
                final Node ref = s.getService();
                if (ref != null && ref.isURI()
                        && ref.getURI().startsWith(InvokeRegistry.WF_INVOKE_SCHEME)) {
                    hit[0] = true;
                }
            }
        });
        return hit[0];
    }

    /** Does a Filter node whose condition contains a CONTAINS survive? */
    private static boolean hasFilterWithContains(final Op op) {
        final boolean[] hit = new boolean[1];
        OpWalker.walk(op, new OpVisitorBase() {
            @Override
            public void visit(final OpFilter f) {
                for (Expr e : f.getExprs()) {
                    if (e instanceof E_StrContains) {
                        hit[0] = true;
                        return;
                    }
                }
            }
        });
        return hit[0];
    }

    /** Grab the InvokeSpec at id 0 (each test starts with a fresh registry). */
    private static InvokeRegistry.InvokeSpec takeFirstInvoke(final InvokeRegistry inv) {
        final InvokeRegistry.InvokeSpec s = inv.take(0L).orElse(null);
        assertThat(s).as("expected an InvokeSpec at id 0").isNotNull();
        return s;
    }

    private static String queryArg(final InvokeRegistry.InvokeSpec spec) {
        return spec.args.get(2).getLiteralLexicalForm();
    }
    private static String optsArg(final InvokeRegistry.InvokeSpec spec) {
        return spec.args.get(3).getLiteralLexicalForm();
    }

    private static List<OpService> collectServices(final Op op) {
        final List<OpService> out = new ArrayList<>();
        OpWalker.walk(op, new OpVisitorBase() {
            @Override public void visit(final OpService s) { out.add(s); }
        });
        return out;
    }

    // ---------------------------------------------------------------------
    // Tests — port from fulltext_rewrite.rs::tests
    // ---------------------------------------------------------------------

    @Test
    public void regexSafePatternFoldsToService() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(REGEX(?label, \"widget\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        final Op out = rw.rewrite(input);
        assertThat(rw.foldCount()).isEqualTo(1);
        assertThat(hasWfInvokeService(out)).isTrue();
    }

    @Test
    public void regexUnsafePatternSkips() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(REGEX(?label, \"^widget\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        final Op out = rw.rewrite(input);
        assertThat(rw.foldCount()).isZero();
        assertThat(hasWfInvokeService(out)).isFalse();
    }

    @Test
    public void regexBackrefSkips() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(REGEX(?label, \"(foo)\\\\1\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        final Op out = rw.rewrite(input);
        assertThat(rw.foldCount()).isZero();
        assertThat(hasWfInvokeService(out)).isFalse();
    }

    @Test
    public void containsWordFolds() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(CONTAINS(?label, \"widget\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        final Op out = rw.rewrite(input);
        assertThat(rw.foldCount()).isEqualTo(1);
        assertThat(hasWfInvokeService(out)).isTrue();
    }

    @Test
    public void containsPartialWordSkips() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(CONTAINS(?label, \"wid\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        final Op out = rw.rewrite(input);
        assertThat(rw.foldCount()).isZero();
        assertThat(hasWfInvokeService(out)).isFalse();
    }

    @Test
    public void strstartsFolds() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(STRSTARTS(?label, \"wid\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        final Op out = rw.rewrite(input);
        assertThat(rw.foldCount()).isEqualTo(1);
        assertThat(hasWfInvokeService(out)).isTrue();
    }

    @Test
    public void langMatchesFoldsIfCovered() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(LANG(?label) = \"en\")\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        final Op out = rw.rewrite(input);
        assertThat(rw.foldCount()).isEqualTo(1);
        assertThat(hasWfInvokeService(out)).isTrue();
    }

    @Test
    public void langMatchesSkipsIfNotCovered() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        // Registry only claims "en"; query asks for "fr".
        final Op input = parseAlgebra(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(LANG(?label) = \"fr\")\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        final Op out = rw.rewrite(input);
        assertThat(rw.foldCount()).isZero();
        assertThat(hasWfInvokeService(out)).isFalse();
    }

    @Test
    public void documentCorpusPredicateNeverFolds() {
        final FulltextRegistry reg = documentCorpusRegistry();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(CONTAINS(?label, \"widget\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        final Op out = rw.rewrite(input);
        // ex:label isn't under any literal-index entry.
        assertThat(rw.foldCount()).isZero();
        assertThat(hasWfInvokeService(out)).isFalse();
    }

    @Test
    public void filterOverConcatSkips() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?a ?b WHERE {\n"
                + "  ?p ex:label ?a ; ex:label ?b .\n"
                + "  FILTER(CONTAINS(CONCAT(?a, ?b), \"widget\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        final Op out = rw.rewrite(input);
        assertThat(rw.foldCount()).isZero();
        assertThat(hasWfInvokeService(out)).isFalse();
    }

    @Test
    public void originalFilterPreserved() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(CONTAINS(?label, \"widget\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        final Op out = rw.rewrite(input);
        assertThat(rw.foldCount()).isEqualTo(1);
        // Safety invariant (memo §06): outer FILTER(CONTAINS(...)) must
        // remain around the joined result.
        assertThat(hasFilterWithContains(out))
                .as("outer FILTER(CONTAINS(...)) must survive the rewrite")
                .isTrue();
        assertThat(hasWfInvokeService(out)).isTrue();
    }

    @Test
    public void lcaseContainsFolds() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(CONTAINS(LCASE(?label), \"Waterproof\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        final Op out = rw.rewrite(input);
        assertThat(rw.foldCount()).isEqualTo(1);
        assertThat(hasWfInvokeService(out)).isTrue();

        final InvokeRegistry.InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(queryArg(spec)).isEqualTo("waterproof");
        assertThat(optsArg(spec)).contains("\"case_insensitive\":true");
    }

    @Test
    public void ucaseContainsFolds() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(CONTAINS(UCASE(?label), \"WATERPROOF\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        final Op out = rw.rewrite(input);
        assertThat(rw.foldCount()).isEqualTo(1);
        assertThat(hasWfInvokeService(out)).isTrue();

        final InvokeRegistry.InvokeSpec spec = takeFirstInvoke(inv);
        // UCASE — the rewrite normalizes to lowercase regardless (backend
        // analyzers case-fold).
        assertThat(queryArg(spec)).isEqualTo("waterproof");
        assertThat(optsArg(spec)).contains("\"case_insensitive\":true");
    }

    @Test
    public void strWrapperFolds() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(CONTAINS(STR(?label), \"widget\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        final Op out = rw.rewrite(input);
        assertThat(rw.foldCount()).isEqualTo(1);
        assertThat(hasWfInvokeService(out)).isTrue();

        final InvokeRegistry.InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(queryArg(spec)).isEqualTo("widget");
        assertThat(optsArg(spec)).doesNotContain("case_insensitive");
    }

    @Test
    public void nestedLcaseStrFolds() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(CONTAINS(LCASE(STR(?label)), \"widget\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        final Op out = rw.rewrite(input);
        assertThat(rw.foldCount()).isEqualTo(1);
        assertThat(hasWfInvokeService(out)).isTrue();

        final InvokeRegistry.InvokeSpec spec = takeFirstInvoke(inv);
        assertThat(optsArg(spec)).contains("\"case_insensitive\":true");
    }

    @Test
    public void concatArgSkips() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?a ?b WHERE {\n"
                + "  ?p ex:label ?a ; ex:label ?b .\n"
                + "  FILTER(CONTAINS(CONCAT(?a, ?b), \"widget\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        final Op out = rw.rewrite(input);
        assertThat(rw.foldCount()).isZero();
        assertThat(hasWfInvokeService(out)).isFalse();
    }

    @Test
    public void substrArgSkips() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(CONTAINS(SUBSTR(?label, 1, 3), \"widget\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        final Op out = rw.rewrite(input);
        assertThat(rw.foldCount()).isZero();
        assertThat(hasWfInvokeService(out)).isFalse();
    }

    @Test
    public void deeplyNestedWrappersCapsOut() {
        final FulltextRegistry reg = productsRegistryWord();
        final InvokeRegistry inv = new InvokeRegistry();
        // Four LCASE layers — one more than the cap.
        final Op input = parseAlgebra(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(CONTAINS(LCASE(LCASE(LCASE(LCASE(?label)))), \"widget\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        final Op out = rw.rewrite(input);
        assertThat(rw.foldCount()).isZero();
        assertThat(hasWfInvokeService(out)).isFalse();
    }

    @Test
    public void emptyRegistryNoop() {
        final FulltextRegistry reg = FulltextRegistry.empty();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra(""
                + "PREFIX ex: <http://ex/>\n"
                + "SELECT ?p ?label WHERE {\n"
                + "  ?p ex:label ?label .\n"
                + "  FILTER(CONTAINS(?label, \"widget\"))\n"
                + "}");
        final FulltextRewrite rw = new FulltextRewrite(reg, inv);
        final Op out = rw.rewrite(input);
        assertThat(rw.foldCount()).isZero();
        assertThat(hasWfInvokeService(out)).isFalse();
        assertThat(collectServices(out)).isEmpty();
    }
}
