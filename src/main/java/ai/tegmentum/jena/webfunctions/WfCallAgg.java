package ai.tegmentum.jena.webfunctions;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.expr.ExprEvalException;
import org.apache.jena.sparql.expr.ExprList;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.expr.aggregate.AccumulatorFactory;
import org.apache.jena.sparql.expr.aggregate.Accumulator;
import org.apache.jena.sparql.expr.aggregate.AggCustom;
import org.apache.jena.sparql.function.FunctionEnv;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

/**
 * ARQ custom aggregate {@code wf:call-agg(<component-url>, ...values)}.
 * On each row, calls the component's {@code aggregate-step} export with the
 * evaluated row values (multiplicity is always 1 — Jena's aggregate model
 * doesn't expose per-row multiplicity). On aggregation close, calls
 * {@code aggregate-finish} and returns the first row's first bound value.
 *
 * <p>Registered under {@link #URI} via {@link WebFunctionInit#register()}.
 */
public final class WfCallAgg implements AccumulatorFactory {

    public static final String URI = WfCall.NAMESPACE + "call-agg";

    @Override
    public Accumulator createAccumulator(final AggCustom agg, final boolean distinct) {
        return new WasmAccumulator(agg.getExprList());
    }

    private static final class WasmAccumulator implements Accumulator {

        private final ExprList exprs;
        private JenaWasmInstance instance;
        private NodeValue error;
        private static final Node EMPTY_NODE = NodeFactory.createLiteralString("");

        WasmAccumulator(final ExprList exprs) {
            if (exprs == null || exprs.size() < 1) {
                throw new IllegalArgumentException(
                        "wf:call-agg requires at least the component URL argument");
            }
            this.exprs = exprs;
        }

        @Override
        public void accumulate(final Binding binding, final FunctionEnv env) {
            if (error != null) return; // skip once a prior step failed
            try {
                if (instance == null) {
                    final Node urlNode = exprs.get(0).eval(binding, env).asNode();
                    instance = new JenaWasmInstance(toUrl(urlNode));
                }
                final Node[] stepArgs = new Node[exprs.size() - 1];
                for (int i = 1; i < exprs.size(); i++) {
                    stepArgs[i - 1] = exprs.get(i).eval(binding, env).asNode();
                }
                instance.aggregateStep(stepArgs, 1L);
            } catch (IOException e) {
                error = NodeValue.makeString("wf:call-agg step failed: " + e.getMessage());
            } catch (ExprEvalException e) {
                error = NodeValue.makeString("wf:call-agg arg eval failed: " + e.getMessage());
            }
        }

        @Override
        public NodeValue getValue() {
            try {
                if (error != null) throw new ExprEvalException(error.asString());
                if (instance == null) return NodeValue.makeNode(EMPTY_NODE);
                final List<WitValueMarshaller.Row> rows = instance.aggregateFinish();
                if (rows.isEmpty() || rows.get(0).values.isEmpty()
                        || rows.get(0).values.get(0) == null) {
                    return NodeValue.makeNode(EMPTY_NODE);
                }
                return NodeValue.makeNode(rows.get(0).values.get(0));
            } catch (IOException e) {
                throw new ExprEvalException("wf:call-agg finish failed: " + e.getMessage(), e);
            } finally {
                if (instance != null) {
                    instance.close();
                    instance = null;
                }
            }
        }

        private static URL toUrl(final Node n) {
            final String raw;
            if (n.isURI()) raw = n.getURI();
            else if (n.isLiteral()) raw = n.getLiteralLexicalForm();
            else throw new ExprEvalException("wf:call-agg: first arg must be an IRI or string");
            try {
                return new URL(raw);
            } catch (MalformedURLException e) {
                throw new ExprEvalException("wf:call-agg: not a valid URL: " + raw, e);
            }
        }
    }
}
