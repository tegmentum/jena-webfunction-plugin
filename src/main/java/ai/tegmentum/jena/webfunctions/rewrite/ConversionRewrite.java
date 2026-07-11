package ai.tegmentum.jena.webfunctions.rewrite;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.TransformCopy;
import org.apache.jena.sparql.algebra.Transformer;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpExtend;
import org.apache.jena.sparql.algebra.op.OpGraph;
import org.apache.jena.sparql.algebra.op.OpJoin;
import org.apache.jena.sparql.algebra.op.OpUnion;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprTransform;
import org.apache.jena.sparql.expr.ExprTransformCopy;
import org.apache.jena.sparql.expr.ExprTransformer;
import org.apache.jena.sparql.expr.ExprVar;
import org.apache.jena.sparql.expr.NodeValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Query-time rewrite of virtual {@code urn:wf:conversion:*} named
 * graphs into computed triples.
 *
 * <p>Mirrors {@code oxigraph-wf/src/conversion_rewrite.rs}. Default-graph
 * queries are untouched — pristine, real triples only. Only patterns
 * explicitly wrapped in {@code GRAPH <urn:wf:conversion:X>} or
 * {@code GRAPH ?g} trigger the rewrite.
 *
 * <ul>
 *   <li>Specific conversion — user names the exact rule. The BGP is
 *       rewritten into a per-triple Join of source BGP + BIND.</li>
 *   <li>Any conversion — user leaves the graph as a variable so the
 *       rewriter unions across every registered rule for the target,
 *       binding {@code ?g} to the rule's virtual graph IRI so the
 *       caller sees which conversion produced each row.</li>
 * </ul>
 *
 * <p>Non-triple GRAPH bodies (nested joins, filters, subqueries) pass
 * through unchanged — v1 handles the flat BGP case only.
 */
public final class ConversionRewrite {

    static final String CONVERSION_SCHEME = "urn:wf:conversion:";

    private ConversionRewrite() {}

    public static Op rewrite(final Op op, final ConversionRegistry registry) {
        if (registry == null || registry.isEmpty()) {
            return op;
        }
        return Transformer.transform(new ConversionTransform(registry), op);
    }

    private static final class ConversionTransform extends TransformCopy {
        private final ConversionRegistry registry;

        ConversionTransform(final ConversionRegistry registry) {
            this.registry = registry;
        }

        @Override
        public Op transform(final OpGraph opGraph, final Op subOp) {
            // Only handle the flat-BGP body case; anything else falls
            // through to the default super.transform copy behaviour so
            // the transformer preserves the ambient tree shape.
            if (!(subOp instanceof OpBGP bgp)) {
                return super.transform(opGraph, subOp);
            }
            final BasicPattern pattern = bgp.getPattern();
            if (pattern.isEmpty()) {
                return super.transform(opGraph, subOp);
            }

            final Node name = opGraph.getNode();
            if (name.isURI()) {
                final String iri = name.getURI();
                if (!iri.startsWith(CONVERSION_SCHEME)) {
                    return super.transform(opGraph, subOp);
                }
                final java.util.Optional<ConversionRegistry.ConversionRule> maybeRule =
                        registry.ruleByGraph(iri);
                if (maybeRule.isEmpty()) {
                    return super.transform(opGraph, subOp);
                }
                final ConversionRegistry.ConversionRule rule = maybeRule.get();
                final List<Op> collected = new ArrayList<>(pattern.size());
                for (Triple tp : pattern) {
                    final Op piece = rewriteTripleSpecific(tp, rule);
                    if (piece == null) {
                        return super.transform(opGraph, subOp);
                    }
                    collected.add(piece);
                }
                return joinAll(collected);
            }

            if (name.isVariable()) {
                final Var graphVar = Var.alloc(name.getName());
                // Every triple must be rewritable against SOME rule for
                // the graph variable to be a conversion-graph iterator.
                // Otherwise the caller genuinely means "any named
                // graph" and we mustn't intercept.
                final List<List<Op>> perTripleAlternatives = new ArrayList<>(pattern.size());
                for (Triple tp : pattern) {
                    if (!tp.getPredicate().isURI()) {
                        return super.transform(opGraph, subOp);
                    }
                    final String predIri = tp.getPredicate().getURI();
                    if (!tp.getObject().isVariable()) {
                        return super.transform(opGraph, subOp);
                    }
                    final Var objVar = Var.alloc(tp.getObject().getName());
                    final List<ConversionRegistry.ConversionRule> rules =
                            registry.rulesForTarget(predIri);
                    if (rules.isEmpty()) {
                        return super.transform(opGraph, subOp);
                    }
                    final List<Op> alts = new ArrayList<>(rules.size());
                    for (ConversionRegistry.ConversionRule rule : rules) {
                        alts.add(buildVariableGraphBranch(
                                tp.getSubject(), objVar, rule, graphVar));
                    }
                    perTripleAlternatives.add(alts);
                }

                // v1 supports single-triple GRAPH ?g bodies only; a
                // multi-triple product blows up combinatorially and the
                // Rust prototype rejects it too.
                if (perTripleAlternatives.size() == 1) {
                    return unionOf(perTripleAlternatives.get(0));
                }
                return super.transform(opGraph, subOp);
            }

            return super.transform(opGraph, subOp);
        }

        private static Op rewriteTripleSpecific(final Triple tp,
                                                final ConversionRegistry.ConversionRule rule) {
            if (!tp.getPredicate().isURI()) return null;
            final String predIri = tp.getPredicate().getURI();
            if (!predIri.equals(rule.targetPredicate)) return null;
            if (!tp.getObject().isVariable()) return null;
            final Var objVar = Var.alloc(tp.getObject().getName());
            final Var srcVar = freshVar(objVar);

            final BasicPattern bp = new BasicPattern();
            bp.add(Triple.create(tp.getSubject(),
                    NodeFactory.createURI(rule.sourcePredicate), srcVar));
            return OpExtend.create(new OpBGP(bp), objVar,
                    substituteSource(rule.parsedExpression, srcVar));
        }

        private static Op buildVariableGraphBranch(final Node subject,
                                                   final Var objVar,
                                                   final ConversionRegistry.ConversionRule rule,
                                                   final Var graphVar) {
            final Var srcVar = freshVar(objVar);
            final BasicPattern bp = new BasicPattern();
            bp.add(Triple.create(subject,
                    NodeFactory.createURI(rule.sourcePredicate), srcVar));
            final Op afterCompute = OpExtend.create(new OpBGP(bp), objVar,
                    substituteSource(rule.parsedExpression, srcVar));
            return OpExtend.create(afterCompute, graphVar,
                    NodeValue.makeNode(NodeFactory.createURI(rule.graphIri)));
        }

        /**
         * Substitute occurrences of {@code ?source} inside a parsed
         * Expression with a fresh variable. Uses Jena's ExprTransformer
         * so every sub-expression shape is covered without hand-listing
         * the cases (matches the spirit of the Rust
         * {@code substitute_source}, but Jena provides the walker
         * infrastructure so we don't have to).
         */
        private static Expr substituteSource(final Expr e, final Var srcVar) {
            final ExprTransform xform = new ExprTransformCopy() {
                @Override
                public Expr transform(final ExprVar exprVar) {
                    if ("source".equals(exprVar.getVarName())) {
                        return new ExprVar(srcVar);
                    }
                    return super.transform(exprVar);
                }
            };
            return ExprTransformer.transform(xform, e);
        }

        /**
         * Fresh variable name distinct from any user-visible one; the
         * object var suffix keeps traces greppable.
         */
        private static Var freshVar(final Var objVar) {
            return Var.alloc("_wfconv_" + objVar.getName());
        }

        private static Op joinAll(final List<Op> patterns) {
            if (patterns.isEmpty()) {
                return new OpBGP();
            }
            Op acc = patterns.get(0);
            for (int i = 1; i < patterns.size(); i++) {
                acc = OpJoin.create(acc, patterns.get(i));
            }
            return acc;
        }

        private static Op unionOf(final List<Op> patterns) {
            if (patterns.isEmpty()) {
                return new OpBGP();
            }
            Op acc = patterns.get(0);
            for (int i = 1; i < patterns.size(); i++) {
                acc = OpUnion.create(acc, patterns.get(i));
            }
            return acc;
        }
    }
}
