package ai.tegmentum.jena.webfunctions.rewrite;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.TransformCopy;
import org.apache.jena.sparql.algebra.Transformer;
import org.apache.jena.sparql.algebra.op.OpExtend;
import org.apache.jena.sparql.algebra.op.OpService;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.core.VarExprList;
import org.apache.jena.sparql.expr.E_Function;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.NodeValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Constant-fold {@code wf:partial(...)} into a
 * {@code wf-invoke:<id>} IRI before the query hits the ARQ evaluator.
 *
 * <p>Mirrors {@code oxigraph-wf/src/partial_rewrite.rs}. Two passes:
 *
 * <ol>
 *   <li>Collect — walk the tree finding every
 *       {@code Extend { var = wf:partial(args) }} where every arg is a
 *       constant. Allocate an {@link InvokeRegistry.InvokeSpec},
 *       remember {@code (variable, iri)}.</li>
 *   <li>Substitute — walk again replacing
 *       {@code Service { name = Variable(v) }} with
 *       {@code Service { name = NamedNode(iri) }} for each collected
 *       pair, and dissolving the Extend into its inner so the variable
 *       no longer needs to be produced.</li>
 * </ol>
 *
 * <p>Two passes because the Extend and its Service usage are typically
 * <em>siblings</em> in a Join, not parent/child — a single pass rooted
 * at the Extend can't reach the Service on the other branch.
 */
public final class PartialRewrite {

    private PartialRewrite() {}

    public static Op rewrite(final Op op, final InvokeRegistry registry) {
        if (registry == null) {
            return op;
        }
        final Map<Var, Node> folds = new HashMap<>();
        final Op afterCollect = Transformer.transform(new CollectAndDissolve(registry, folds), op);
        if (folds.isEmpty()) {
            return afterCollect;
        }
        return Transformer.transform(new SubstituteServiceName(folds), afterCollect);
    }

    /**
     * Pass one: find foldable Extend nodes, allocate specs, dissolve
     * the Extend by returning its inner directly.
     */
    private static final class CollectAndDissolve extends TransformCopy {
        private final InvokeRegistry registry;
        private final Map<Var, Node> folds;

        CollectAndDissolve(final InvokeRegistry registry, final Map<Var, Node> folds) {
            this.registry = registry;
            this.folds = folds;
        }

        @Override
        public Op transform(final OpExtend opExtend, final Op subOp) {
            final VarExprList vel = opExtend.getVarExprList();
            // Collect the non-foldable assignments; if any remain, keep
            // an Extend containing them and pass the folded ones off to
            // the registry.
            final VarExprList kept = new VarExprList();
            for (Var v : vel.getVars()) {
                final Expr expr = vel.getExpr(v);
                final Node iri = tryFoldPartial(expr, registry);
                if (iri != null) {
                    folds.put(v, iri);
                } else {
                    kept.add(v, expr);
                }
            }
            if (kept.getVars().isEmpty()) {
                // Entire Extend dissolves — return the inner subOp.
                return subOp;
            }
            if (kept.size() == vel.size()) {
                // Nothing folded; preserve the original shape.
                return super.transform(opExtend, subOp);
            }
            return OpExtend.create(subOp, kept);
        }

        /**
         * If the expression is {@code wf:partial(<wasm>, args...)} and
         * every arg is a constant, allocate a spec and return the
         * {@code wf-invoke:<id>} IRI node.
         */
        private static Node tryFoldPartial(final Expr expr, final InvokeRegistry registry) {
            if (!(expr instanceof E_Function fn)) {
                return null;
            }
            if (!InvokeRegistry.WF_PARTIAL_IRI.equals(fn.getFunctionIRI())) {
                return null;
            }
            final List<Expr> args = fn.getArgs();
            if (args == null || args.isEmpty()) {
                return null;
            }
            final Expr first = args.get(0);
            final String wasmUrl = constantAsUrl(first);
            if (wasmUrl == null) {
                return null;
            }
            final List<Node> constArgs = new ArrayList<>(args.size() - 1);
            for (int i = 1; i < args.size(); i++) {
                final Expr a = args.get(i);
                if (!a.isConstant()) {
                    return null;
                }
                final NodeValue nv = a.getConstant();
                constArgs.add(nv.asNode());
            }
            final long id = registry.insert(new InvokeRegistry.InvokeSpec(wasmUrl, constArgs));
            return NodeFactory.createURI(InvokeRegistry.iriFor(id));
        }

        private static String constantAsUrl(final Expr e) {
            if (!e.isConstant()) return null;
            final NodeValue nv = e.getConstant();
            final Node n = nv.asNode();
            if (n.isURI()) return n.getURI();
            if (n.isLiteral()) return n.getLiteralLexicalForm();
            return null;
        }
    }

    /**
     * Pass two: replace {@code Service { name = ?v }} with
     * {@code Service { name = <iri> }} for every collected pair.
     */
    private static final class SubstituteServiceName extends TransformCopy {
        private final Map<Var, Node> folds;

        SubstituteServiceName(final Map<Var, Node> folds) {
            this.folds = folds;
        }

        @Override
        public Op transform(final OpService opService, final Op subOp) {
            final Node svc = opService.getService();
            if (svc.isVariable()) {
                final Node replacement = folds.get(Var.alloc(svc.getName()));
                if (replacement != null) {
                    return new OpService(replacement, subOp, opService.getSilent());
                }
            }
            return super.transform(opService, subOp);
        }
    }

}
