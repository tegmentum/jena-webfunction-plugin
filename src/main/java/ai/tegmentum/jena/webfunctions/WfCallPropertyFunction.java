package ai.tegmentum.jena.webfunctions;

import org.apache.jena.graph.Node;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingBuilder;
import org.apache.jena.sparql.engine.iterator.QueryIterPlainWrapper;
import org.apache.jena.sparql.expr.ExprEvalException;
import org.apache.jena.sparql.pfunction.PFuncSimpleAndList;
import org.apache.jena.sparql.pfunction.PropFuncArg;
import org.apache.jena.sparql.util.IterLib;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * SPARQL property function {@code ?subject wf:call (<wasm-url> args...)}.
 *
 * <p>Binds {@code ?subject} to the first column of each row returned by the
 * component's {@code evaluate} export — one binding per row. Use this shape
 * when a single wasm call should produce multiple SPARQL result rows;
 * {@code BIND(wf:call(...))} (the filter function form) captures only the
 * first value of the first row, and {@code SERVICE <wasm-url>} exposes the
 * full multi-var / multi-row shape.
 */
public final class WfCallPropertyFunction extends PFuncSimpleAndList {

    public static final String URI = WfCall.URI;

    @Override
    public QueryIterator execEvaluated(
            final Binding binding,
            final Node subject,
            final Node predicate,
            final PropFuncArg object,
            final ExecutionContext ctx) {
        final List<Node> objectList = object.getArgList();
        if (objectList.isEmpty()) {
            throw new ExprEvalException(
                    "wf:call (property function) requires at least the component URL");
        }

        final URL wasmUrl = toUrl(objectList.get(0));
        final Node[] args = new Node[objectList.size() - 1];
        for (int i = 1; i < objectList.size(); i++) {
            args[i - 1] = objectList.get(i);
        }

        final List<WitValueMarshaller.Row> rows;
        try (JenaWasmInstance instance = new JenaWasmInstance(wasmUrl)) {
            rows = instance.evaluate(args);
        } catch (IOException e) {
            throw new ExprEvalException("wf:call: " + e.getMessage(), e);
        }

        if (rows.isEmpty()) {
            return IterLib.noResults(ctx);
        }

        final List<Binding> out = new ArrayList<>(rows.size());
        for (WitValueMarshaller.Row row : rows) {
            if (row.values.isEmpty() || row.values.get(0) == null) continue;
            final Node firstValue = row.values.get(0);

            if (subject.isVariable()) {
                final BindingBuilder bb = BindingBuilder.create(binding);
                bb.add(Var.alloc(subject.getName()), firstValue);
                out.add(bb.build());
            } else {
                // Ground subject — treat as an existence check: keep the binding
                // only if the subject Node equals the row's first value.
                if (subject.equals(firstValue)) {
                    out.add(binding);
                }
            }
        }

        final Iterator<Binding> it = out.iterator();
        return QueryIterPlainWrapper.create(it, ctx);
    }

    private static URL toUrl(final Node n) {
        final String raw;
        if (n.isURI()) raw = n.getURI();
        else if (n.isLiteral()) raw = n.getLiteralLexicalForm();
        else throw new ExprEvalException(
                "wf:call (property function): first list element must be an IRI or string");
        try {
            return new URL(raw);
        } catch (MalformedURLException e) {
            throw new ExprEvalException("wf:call: not a valid URL: " + raw, e);
        }
    }
}
