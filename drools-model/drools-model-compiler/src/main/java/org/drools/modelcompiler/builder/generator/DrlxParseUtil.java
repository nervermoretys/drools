/*
 * Copyright 2005 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.modelcompiler.builder.generator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.drools.core.util.ClassUtils;
import org.drools.core.util.index.IndexUtil;
import org.drools.core.util.index.IndexUtil.ConstraintType;
import org.drools.drlx.DrlxParser;
import org.drools.javaparser.JavaParser;
import org.drools.javaparser.ast.Node;
import org.drools.javaparser.ast.body.Parameter;
import org.drools.javaparser.ast.drlx.expr.DrlxExpression;
import org.drools.javaparser.ast.drlx.expr.InlineCastExpr;
import org.drools.javaparser.ast.drlx.expr.NullSafeFieldAccessExpr;
import org.drools.javaparser.ast.expr.ArrayAccessExpr;
import org.drools.javaparser.ast.expr.ArrayCreationExpr;
import org.drools.javaparser.ast.expr.BinaryExpr;
import org.drools.javaparser.ast.expr.BinaryExpr.Operator;
import org.drools.javaparser.ast.expr.BooleanLiteralExpr;
import org.drools.javaparser.ast.expr.CastExpr;
import org.drools.javaparser.ast.expr.CharLiteralExpr;
import org.drools.javaparser.ast.expr.DoubleLiteralExpr;
import org.drools.javaparser.ast.expr.EnclosedExpr;
import org.drools.javaparser.ast.expr.Expression;
import org.drools.javaparser.ast.expr.FieldAccessExpr;
import org.drools.javaparser.ast.expr.InstanceOfExpr;
import org.drools.javaparser.ast.expr.IntegerLiteralExpr;
import org.drools.javaparser.ast.expr.LambdaExpr;
import org.drools.javaparser.ast.expr.LiteralExpr;
import org.drools.javaparser.ast.expr.LongLiteralExpr;
import org.drools.javaparser.ast.expr.MethodCallExpr;
import org.drools.javaparser.ast.expr.NameExpr;
import org.drools.javaparser.ast.expr.NullLiteralExpr;
import org.drools.javaparser.ast.expr.SimpleName;
import org.drools.javaparser.ast.expr.StringLiteralExpr;
import org.drools.javaparser.ast.expr.ThisExpr;
import org.drools.javaparser.ast.expr.UnaryExpr;
import org.drools.javaparser.ast.nodeTypes.NodeWithOptionalScope;
import org.drools.javaparser.ast.nodeTypes.NodeWithSimpleName;
import org.drools.javaparser.ast.stmt.BlockStmt;
import org.drools.javaparser.ast.stmt.ExpressionStmt;
import org.drools.javaparser.ast.type.PrimitiveType;
import org.drools.javaparser.ast.type.ReferenceType;
import org.drools.javaparser.ast.type.Type;
import org.drools.javaparser.ast.type.UnknownType;
import org.drools.modelcompiler.builder.PackageModel;
import org.drools.modelcompiler.util.ClassUtil;
import org.kie.soup.project.datamodel.commons.types.TypeResolver;

import static org.drools.core.util.ClassUtils.getter2property;
import static org.drools.modelcompiler.util.ClassUtil.findMethod;

public class DrlxParseUtil {

    public static final NameExpr _THIS_EXPR = new NameExpr("_this");

    public static IndexUtil.ConstraintType toConstraintType(Operator operator) {
        switch (operator) {
            case EQUALS:
                return ConstraintType.EQUAL;
            case NOT_EQUALS:
                return ConstraintType.NOT_EQUAL;
            case GREATER:
                return ConstraintType.GREATER_THAN;
            case GREATER_EQUALS:
                return ConstraintType.GREATER_OR_EQUAL;
            case LESS:
                return ConstraintType.LESS_THAN;
            case LESS_EQUALS:
                return ConstraintType.LESS_OR_EQUAL;
            default:
                return ConstraintType.UNKNOWN;
        }
    }

    public static TypedExpression toTypedExpression(RuleContext context, PackageModel packageModel, Class<?> patternType, Expression drlxExpr,
                                                    List<String> usedDeclarations, Set<String> reactOnProperties) {

        Class<?> typeCursor = patternType;

        if(drlxExpr instanceof EnclosedExpr) {
            drlxExpr = ((EnclosedExpr) drlxExpr).getInner();
        }

        if (drlxExpr instanceof UnaryExpr) {
            UnaryExpr unaryExpr = (UnaryExpr) drlxExpr;
            TypedExpression typedExpr = toTypedExpression( context, packageModel, patternType, unaryExpr.getExpression(), usedDeclarations, reactOnProperties );
            return new TypedExpression( new UnaryExpr( typedExpr.getExpression(), unaryExpr.getOperator() ), typedExpr.getType() );

        } else if (drlxExpr instanceof BinaryExpr) {
            BinaryExpr binaryExpr = (BinaryExpr) drlxExpr;

            Operator operator = binaryExpr.getOperator();

            TypedExpression left = DrlxParseUtil.toTypedExpression( context, packageModel, patternType, binaryExpr.getLeft(), usedDeclarations, reactOnProperties );
            TypedExpression right = DrlxParseUtil.toTypedExpression( context, packageModel, patternType, binaryExpr.getRight(), usedDeclarations, reactOnProperties );

            BinaryExpr combo = new BinaryExpr( left.getExpression(), right.getExpression(), operator );
            return new TypedExpression( combo, left.getType() );

        } else if (drlxExpr instanceof LiteralExpr) {
            return new TypedExpression(drlxExpr);

        } else if (drlxExpr instanceof ThisExpr) {
            return new TypedExpression(new NameExpr("_this"));

        } else if (drlxExpr instanceof CastExpr) {
            CastExpr castExpr = (CastExpr)drlxExpr;
            return new TypedExpression(castExpr, getClassFromContext(context.getPkg().getTypeResolver(), castExpr.getType().asString()));

        } else if (drlxExpr instanceof NameExpr) {
            String name = drlxExpr.toString();
            if (context.getDeclarationById(name).isPresent()) {
                // then drlxExpr is a single NameExpr referring to a binding, e.g.: "$p1".
                usedDeclarations.add(name);
                return new TypedExpression(drlxExpr);
            } if (context.queryParameters.stream().anyMatch(qp -> qp.name.equals(name))) {
                // then drlxExpr is a single NameExpr referring to a query parameter, e.g.: "$p1".
                usedDeclarations.add(name);
                return new TypedExpression(drlxExpr);
            } else if(packageModel.getGlobals().containsKey(name)){
                Expression plusThis = new NameExpr(name);
                usedDeclarations.add(name);
                return new TypedExpression(plusThis, packageModel.getGlobals().get(name));
            } else {
                TypedExpression expression;
                try {
                    expression = nameExprToMethodCallExpr(name, typeCursor);
                } catch (IllegalArgumentException e) {
                    String unificationVariable = context.getOrCreateUnificationId(name);
                    expression = new TypedExpression(unificationVariable, typeCursor, name);
                    return expression;
                }
                reactOnProperties.add(name);
                Expression plusThis = prepend(new NameExpr("_this"), (MethodCallExpr) expression.getExpression());
                return new TypedExpression(plusThis, expression.getType(), name);
            }
        } else if (drlxExpr instanceof FieldAccessExpr || drlxExpr instanceof MethodCallExpr) {
            List<Node> childNodes = drlxExpr.getChildNodes();
            Node firstNode = childNodes.get(0);

            boolean isInLineCast = firstNode instanceof InlineCastExpr;
            if (isInLineCast) {
                InlineCastExpr inlineCast = (InlineCastExpr) firstNode;
                try {
                    typeCursor = context.getPkg().getTypeResolver().resolveType(inlineCast.getType().toString());
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }
                firstNode = inlineCast.getExpression();
            }

            Expression previous;

            if (firstNode instanceof NameExpr) {
                NameExpr firstNodeName = (NameExpr) firstNode;
                String firstName = firstNodeName.getName().getIdentifier();
                Optional<DeclarationSpec> declarationById = context.getDeclarationById(firstName);
                if (declarationById.isPresent()) {
                    // do NOT append any reactOnProperties.
                    // because reactOnProperties is referring only to the properties of the type of the pattern, not other declarations properites.
                    usedDeclarations.add(firstName);
                    if (!isInLineCast) {
                        typeCursor = declarationById.get().declarationClass;
                    }
                    previous = new NameExpr(firstName);
                } else {

                    // In OOPath a declaration is based on a position rather than a name.
                    // Only an OOPath chunk can have a backreference expression
                    Optional<DeclarationSpec> backReference = Optional.empty();
                    if(firstNodeName.getBackReferencesCount()  > 0) {
                        List<DeclarationSpec> ooPathDeclarations = context.getOOPathDeclarations();
                        DeclarationSpec backReferenceDeclaration = ooPathDeclarations.get(ooPathDeclarations.size() - 1 - firstNodeName.getBackReferencesCount());
                        typeCursor = backReferenceDeclaration.declarationClass;
                        backReference = Optional.of(backReferenceDeclaration);
                        usedDeclarations.add(backReferenceDeclaration.getBindingId());
                    }

                    Method firstAccessor = ClassUtils.getAccessor(typeCursor, firstName);
                    if (firstAccessor != null) {
                        reactOnProperties.add(firstName);
                        typeCursor = firstAccessor.getReturnType();
                        NameExpr thisAccessor = new NameExpr("_this");
                        final NameExpr scope = backReference.map(d -> new NameExpr(d.getBindingId())).orElse(thisAccessor);
                        previous = new MethodCallExpr(scope, firstAccessor.getName());
                    } else {
                        throw new UnsupportedOperationException("firstNode I don't know about");
                        // TODO would it be fine to assume is a global if it's not in the declarations and not the first accesssor in a chain?
                    }
                }
            } else if (firstNode instanceof ThisExpr) {
                previous = new NameExpr("_this");
                if (childNodes.size() > 1 && !isInLineCast) {
                    SimpleName fieldName = null;
                    if (childNodes.get(1) instanceof NameExpr) {
                        fieldName = (( NameExpr ) childNodes.get( 1 )).getName();
                    } else if (childNodes.get(1) instanceof SimpleName) {
                        fieldName = ( SimpleName ) childNodes.get( 1 );
                    }
                    if (fieldName != null) {
                        if (drlxExpr instanceof MethodCallExpr) {
                            reactOnProperties.add( getter2property( fieldName.getIdentifier() ) );
                        } else {
                            reactOnProperties.add( fieldName.getIdentifier() );
                        }
                    }
                }
            } else if (firstNode instanceof FieldAccessExpr && ((FieldAccessExpr) firstNode).getScope() instanceof ThisExpr) {
                String firstName = ((FieldAccessExpr) firstNode).getName().getIdentifier();
                Method firstAccessor = ClassUtils.getAccessor(typeCursor, firstName);
                if (firstAccessor != null) {
                    reactOnProperties.add(firstName);
                    typeCursor = firstAccessor.getReturnType();
                    previous = new MethodCallExpr(new NameExpr("_this"), firstAccessor.getName());
                } else {
                    throw new UnsupportedOperationException("firstNode I don't know about");
                    // TODO would it be fine to assume is a global if it's not in the declarations and not the first accesssor in a chain?
                }
            } else if (firstNode instanceof SimpleName) {
                previous = new NameExpr("_this");
                SimpleName fieldName = ( SimpleName ) firstNode;
                String name = drlxExpr instanceof MethodCallExpr ? getter2property( fieldName.getIdentifier() ) : fieldName.getIdentifier();
                reactOnProperties.add( name );
                TypedExpression expression = nameExprToMethodCallExpr(name, typeCursor);
                Expression plusThis = prepend(new NameExpr("_this"), (MethodCallExpr) expression.getExpression());
                return new TypedExpression(plusThis, expression.getType());
            } else {
                throw new UnsupportedOperationException("Unknown node: " + firstNode);
            }

            childNodes = drlxExpr.getChildNodes().subList(1, drlxExpr.getChildNodes().size());

            TypedExpression typedExpression = new TypedExpression();
            if (isInLineCast) {
                ReferenceType castType = JavaParser.parseClassOrInterfaceType(typeCursor.getName());
                typedExpression.setPrefixExpression(new InstanceOfExpr(previous, castType));
                previous = new EnclosedExpr(new CastExpr(castType, previous));
            }
            if (drlxExpr instanceof NullSafeFieldAccessExpr) {
                typedExpression.setPrefixExpression(new BinaryExpr(previous, new NullLiteralExpr(), Operator.NOT_EQUALS));
            }

            for (Node part : childNodes) {
                String field = part.toString();
                Method accessor = ClassUtils.getAccessor(typeCursor, field);
                if (accessor == null) {
                    throw new IllegalStateException("Unknown field '" + field + "' on type " + typeCursor);
                }
                typeCursor = accessor.getReturnType();
                previous = new MethodCallExpr(previous, accessor.getName());
            }

            return typedExpression.setExpression(previous).setType(typeCursor);
        }

        throw new UnsupportedOperationException();
    }

    public static TypedExpression nameExprToMethodCallExpr(String name, Class<?> clazz) {
        Method accessor = ClassUtils.getAccessor(clazz, name);
        if (accessor != null) {
            MethodCallExpr body = new MethodCallExpr( null, accessor.getName() );
            return new TypedExpression( body, accessor.getReturnType() );
        }
        try {
            Field field = clazz.getField( name );
            FieldAccessExpr expr = new FieldAccessExpr( null, name );
            return new TypedExpression( expr, field.getType() );
        } catch (NoSuchFieldException e) {
            throw new IllegalArgumentException( "Unknown field " + name + " on " + clazz );
        }
    }

    public static Class<?> returnTypeOfMethodCallExpr(TypeResolver typeResolver, MethodCallExpr methodCallExpr, Class<?> clazz) {
        final Class[] argsType = methodCallExpr.getArguments().stream()
                .map((Expression e) -> getExpressionType(typeResolver, e))
                .toArray(Class[]::new);
        return findMethod(clazz, methodCallExpr.getNameAsString(), argsType).getReturnType();
    }

    public static Class<?> getExpressionType(TypeResolver typeResolver, Expression expr) {
        if(expr instanceof BooleanLiteralExpr) {
            return Boolean.class;
        } else if(expr instanceof CharLiteralExpr) {
            return Character.class;
        } else if(expr instanceof DoubleLiteralExpr) {
            return Double.class;
        } else if(expr instanceof IntegerLiteralExpr) {
            return Integer.class;
        } else if(expr instanceof LongLiteralExpr) {
            return Long.class;
        } else if(expr instanceof NullLiteralExpr) {
            return ClassUtil.NullType.class;
        } else if(expr instanceof StringLiteralExpr) {
            return String.class;
        } else if(expr instanceof ArrayAccessExpr) {
            return getClassFromContext(typeResolver, ((ArrayCreationExpr)((ArrayAccessExpr) expr).getName()).getElementType().asString());
        } else if(expr instanceof ArrayCreationExpr) {
            return getClassFromContext(typeResolver, ((ArrayCreationExpr) expr).getElementType().asString());
        } else {
            throw new RuntimeException("Unknown expression type: " + expr);
        }
    }

    public static Expression prepend(Expression scope, Expression expr) {
        final Optional<Expression> rootNode = findRootNode(expr);

        if (!rootNode.isPresent()) {
            throw new UnsupportedOperationException("No root found");
        }

        rootNode.map(f -> {
            if (f instanceof NodeWithOptionalScope<?>) {
                ((NodeWithOptionalScope) f).setScope(scope);
            }
            return f;
        });

        return expr;
    }

    public static Optional<Expression> findRootNode(Expression expr) {

        if (expr instanceof NodeWithOptionalScope) {
            final NodeWithOptionalScope<?> exprWithScope = (NodeWithOptionalScope) expr;

            return exprWithScope.getScope().map(DrlxParseUtil::findRootNode).orElse(Optional.of(expr));
        } else if(expr instanceof NameExpr) {
            return Optional.of(expr);
        }

        return Optional.empty();
    }

    public static RemoveRootNodeResult removeRootNode(Expression expr) {
        Optional<Expression> rootNode = findRootNode(expr);

        if(rootNode.isPresent()) {
            Expression root = rootNode.get();
            Optional<Node> parent = root.getParentNode();

            parent.ifPresent(p -> p.remove(root));

            return new RemoveRootNodeResult( rootNode, (Expression) parent.orElse(expr));
        }
        return new RemoveRootNodeResult(rootNode, expr);
    }

    public static class RemoveRootNodeResult {
        Optional<Expression> rootNode;
        Expression withoutRootNode;

        public RemoveRootNodeResult(Optional<Expression> rootNode, Expression withoutRootNode) {
            this.rootNode = rootNode;
            this.withoutRootNode = withoutRootNode;
        }
    }

    public static String toVar(String key) {
        return "var_" + key;
    }

    public static BlockStmt parseBlock(String ruleConsequenceAsBlock) {
        return JavaParser.parseBlock(String.format("{\n%s\n}", ruleConsequenceAsBlock)); // if the RHS is composed only of a line of comment like `//do nothing.` then JavaParser would fail to recognize the ending }
    }

    public static Expression generateLambdaWithoutParameters(Collection<String> usedDeclarations, Expression expr) {
        LambdaExpr lambdaExpr = new LambdaExpr();
        lambdaExpr.setEnclosingParameters( true );
        lambdaExpr.addParameter( new Parameter(new UnknownType(), "_this" ) );
        usedDeclarations.stream().map( s -> new Parameter( new UnknownType(), s ) ).forEach( lambdaExpr::addParameter );
        lambdaExpr.setBody( new ExpressionStmt(expr ) );
        return lambdaExpr;
    }



    public static TypedExpression toMethodCallWithClassCheck(Expression expr, Class<?> clazz, TypeResolver typeResolver) {

        final Deque<ParsedMethod> callStackLeftToRight = new LinkedList<>();

        createExpressionCall(expr, callStackLeftToRight);

        List<Expression> methodCall = new ArrayList<>();
        Class<?> previousClass = clazz;
        for (ParsedMethod e : callStackLeftToRight) {
            if (e.expression instanceof NameExpr || e.expression instanceof FieldAccessExpr) {
                TypedExpression te = nameExprToMethodCallExpr(e.fieldToResolve, previousClass);
                Class<?> returnType = te.getType();
                methodCall.add(te.getExpression());
                previousClass = returnType;
            } else if (e.expression instanceof MethodCallExpr) {
                Class<?> returnType = returnTypeOfMethodCallExpr(typeResolver, (MethodCallExpr) e.expression, previousClass);
                MethodCallExpr cloned = ((MethodCallExpr) e.expression.clone()).removeScope();
                methodCall.add(cloned);
                previousClass = returnType;
            }
        }

        Expression call = methodCall.stream()
                .reduce((a, b) -> {
                    ((NodeWithOptionalScope) b).setScope(a);
                    return b;
                }).orElseThrow(() -> new UnsupportedOperationException("No Expression converted"));

        return new TypedExpression(call, previousClass);
    }

    private static Expression createExpressionCall(Expression expr, Deque<ParsedMethod> expressions) {

        if(expr instanceof NodeWithSimpleName) {
            NodeWithSimpleName fae = (NodeWithSimpleName)expr;
            expressions.push(new ParsedMethod(expr, fae.getName().asString()));
        }

        if (expr instanceof NodeWithOptionalScope) {
            final NodeWithOptionalScope<?> exprWithScope = (NodeWithOptionalScope) expr;

            exprWithScope.getScope().map((Expression scope) -> createExpressionCall(scope, expressions));
        } else if (expr instanceof FieldAccessExpr) {
            // Cannot recurse over getScope() as FieldAccessExpr doesn't support the NodeWithOptionalScope,
            // it will support a new interface to traverse among scopes called NodeWithTraversableScope so
            // we can merge this and the previous branch
            createExpressionCall(((FieldAccessExpr) expr).getScope(), expressions);
        }

        return expr;
    }

    static class ParsedMethod {
        final Expression expression;

        final String fieldToResolve;

        public ParsedMethod(Expression expression, String fieldToResolve) {
            this.expression = expression;
            this.fieldToResolve = fieldToResolve;
        }
        @Override
        public String toString() {
            return "{" +
                    "expression=" + expression +
                    ", fieldToResolve='" + fieldToResolve + '\'' +
                    '}';
        }

    }

    public static Type classToReferenceType(Class<?> declClass) {
        Type parsedType = JavaParser.parseType(declClass.getCanonicalName());
        return parsedType instanceof PrimitiveType ?
                ((PrimitiveType) parsedType).toBoxedType() :
                parsedType.getElementType();
    }

    public static Type toType(Class<?> declClass) {
        return JavaParser.parseType(declClass.getCanonicalName());
    }

    public static Optional<String> findBindingIdFromDotExpression(String expression) {
        int dot = expression.indexOf( '.' );
        if ( dot < 0 ) {
            return Optional.empty();
        }
        return Optional.of(expression.substring(0, dot));
    }

    public static Optional<String> findBindingIdFromFunctionCallExpression(String expression) {
        final Optional<Expression> parsedExpression = Optional.<DrlxExpression>ofNullable(DrlxParser.parseExpression(expression))
                .map(DrlxExpression::getExpr);

        return parsedExpression.flatMap(DrlxParseUtil::findLeafOverArgument).map(Object::toString);
    }

    private static Optional<Expression> findLeafOverArgument(Expression expr) {
        if (expr instanceof MethodCallExpr) {
            final Optional<Expression> optFirstArgument = ((MethodCallExpr)expr).getArguments().stream().findFirst();
            return optFirstArgument
                    .flatMap(DrlxParseUtil::findRootNode)
                    .flatMap(DrlxParseUtil::findLeafOverArgument);
        } else if (expr instanceof NameExpr) {
            return Optional.of(expr);
        }
        return Optional.empty();
    }

    public static Class<?> getClassFromContext(TypeResolver typeResolver, String className) {
        Class<?> patternType;
        try {
            patternType = typeResolver.resolveType(className);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException( e );
        }
        return patternType;
    }

}
