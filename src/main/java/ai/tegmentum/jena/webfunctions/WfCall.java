package ai.tegmentum.jena.webfunctions;

import org.apache.jena.graph.Node;
import org.apache.jena.sparql.ARQInternalErrorException;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.expr.ExprEvalException;
import org.apache.jena.sparql.expr.ExprList;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.Function;
import org.apache.jena.sparql.function.FunctionEnv;
import org.apache.jena.sparql.util.Context;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

/**
 * ARQ filter function {@code wf:call(component-url, args...)}. Loads the WASM
 * component at {@code component-url} (IRI or string literal), invokes its
 * {@code evaluate(list<value>)} export, and returns the first binding value of
 * the first row as the function's {@link NodeValue}.
 *
 * <p>Registered under the namespace {@code http://tegmentum.ai/ns/webfunction/}.
 */
public final class WfCall implements Function {

    public static final String NAMESPACE = "http://tegmentum.ai/ns/webfunction/";
    public static final String URI       = NAMESPACE + "call";

    @Override
    public void build(final String uri, final ExprList args, final Context context) {
        if (args == null || args.size() < 1) {
            throw new ARQInternalErrorException(
                    "wf:call requires at least the component URL argument");
        }
    }

    @Override
    public NodeValue exec(final Binding binding, final ExprList args, final String uri,
                          final FunctionEnv env) {
        if (args.size() < 1) {
            throw new ExprEvalException("wf:call: need at least the component URL");
        }
        final NodeValue urlNv = args.get(0).eval(binding, env);
        final URL componentUrl = toUrl(urlNv);

        final Node[] callArgs = new Node[args.size() - 1];
        for (int i = 1; i < args.size(); i++) {
            callArgs[i - 1] = args.get(i).eval(binding, env).asNode();
        }

        try (JenaWasmInstance instance = new JenaWasmInstance(componentUrl)) {
            final List<WitValueMarshaller.Row> rows = instance.evaluate(callArgs);
            if (rows.isEmpty()) {
                throw new ExprEvalException("wf:call: component returned no rows");
            }
            final WitValueMarshaller.Row row = rows.get(0);
            if (row.values.isEmpty() || row.values.get(0) == null) {
                throw new ExprEvalException("wf:call: first row has no bound values");
            }
            return NodeValue.makeNode(row.values.get(0));
        } catch (IOException e) {
            throw new ExprEvalException("wf:call: " + e.getMessage(), e);
        }
    }

    private static URL toUrl(final NodeValue nv) {
        final Node n = nv.asNode();
        final String raw;
        if (n.isURI()) {
            raw = n.getURI();
        } else if (n.isLiteral()) {
            raw = n.getLiteralLexicalForm();
        } else {
            throw new ExprEvalException("wf:call: first argument must be an IRI or string");
        }
        try {
            return new URL(raw);
        } catch (MalformedURLException e) {
            throw new ExprEvalException("wf:call: not a valid URL: " + raw, e);
        }
    }
}
