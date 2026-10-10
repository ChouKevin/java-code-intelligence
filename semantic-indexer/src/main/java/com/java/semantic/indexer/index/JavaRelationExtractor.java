package com.java.semantic.indexer.index;

import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.MethodAmbiguityException;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory;
import com.github.javaparser.symbolsolver.resolution.naming.NameCategory;
import com.github.javaparser.symbolsolver.resolution.naming.NameLogic;
import com.java.semantic.model.index.RelationContract;
import com.java.semantic.model.index.RelationContract.Fact;
import com.java.semantic.model.index.RelationContract.Kind;
import com.java.semantic.model.index.RelationContract.Reason;
import com.java.semantic.model.index.RelationContract.ResolutionState;
import com.java.semantic.model.index.RelationContract.SourceSite;
import com.java.semantic.model.index.RelationContract.Target;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Written Java associations with solver-proven targets or explicit safe uncertainty. */
public final class JavaRelationExtractor {
    public void extract(ParsedJavaUnit unit, SourceResolutionSession session, Consumer<Fact> sink) {
        if (unit.ast().isEmpty() || !session.inScope(unit.syntax().file().path())) return;
        Set<String> emitted = new HashSet<>();
        Consumer<Fact> unique = fact -> { if (emitted.add(fact.relationId())) sink.accept(fact); };
        unit.ast().orElseThrow().walk(node -> {
            if (node instanceof MethodCallExpr call) {
                resolve(Kind.CALL, unit, call, session, call::resolve, unique);
            } else if (node instanceof ObjectCreationExpr creation) {
                resolve(Kind.CONSTRUCT, unit, creation, session, creation::resolve, unique);
            } else if (node instanceof ExplicitConstructorInvocationStmt invocation) {
                resolve(Kind.CONSTRUCT, unit, invocation, session, invocation::resolve, unique);
            } else if (node instanceof MethodReferenceExpr reference) {
                resolve(Kind.METHOD_REFERENCE, unit, reference, session, reference::resolve, unique);
            } else if (node instanceof ClassOrInterfaceType type) {
                // A qualified type's nested scope is syntax qualification, not another type occurrence.
                if (type.getParentNode().filter(parent -> parent instanceof ClassOrInterfaceType outer
                        && outer.getScope().filter(scope -> scope == type).isPresent()).isPresent()) return;
                resolve(typeKind(type), unit, type, session,
                        () -> type.resolve().asReferenceType().getTypeDeclaration()
                                .orElseThrow(() -> new UnresolvedRelationException(Reason.MISSING_TYPE)), unique);
            } else if (node instanceof AnnotationExpr annotation) {
                resolve(Kind.REFERENCE, unit, annotation, session, annotation::resolve, unique);
            } else if (node instanceof FieldAccessExpr field) {
                reference(unit, field, session, field::resolve, unique);
            } else if (node instanceof NameExpr name) {
                reference(unit, name, session, name::resolve, unique);
            } else if (node instanceof MethodDeclaration method && !method.isStatic() && !method.isPrivate()) {
                overrides(unit, method, session, unique);
            }
        });
    }

    private static NameCategory expressionCategory(Expression expression, SourceResolutionSession session) {
        if (!NameLogic.isAName(expression)) return NameCategory.EXPRESSION_NAME;
        if (expression instanceof NameExpr name) {
            // Values take precedence over identically named types, including static-imported fields.
            if (JavaParserFactory.getContext(name, session).solveSymbol(name.getNameAsString()).isSolved())
                return NameCategory.EXPRESSION_NAME;
            if (JavaParserFactory.getContext(name, session).solveType(name.getNameAsString()).isSolved())
                return NameCategory.TYPE_NAME;
            return NameLogic.classifyReference(name, session);
        }
        FieldAccessExpr qualified = expression.asFieldAccessExpr();
        NameCategory scope = expressionCategory(qualified.getScope(), session);
        if (scope != NameCategory.PACKAGE_NAME && scope != NameCategory.TYPE_NAME)
            return NameCategory.EXPRESSION_NAME;
        if (JavaParserFactory.getContext(qualified, session).solveType(NameLogic.nameAsString(qualified)).isSolved())
            return NameCategory.TYPE_NAME;
        return scope == NameCategory.PACKAGE_NAME ? NameCategory.PACKAGE_NAME : NameCategory.EXPRESSION_NAME;
    }

    private static void reference(ParsedJavaUnit unit, Expression expression, SourceResolutionSession session,
            Supplier<? extends ResolvedValueDeclaration> resolver, Consumer<Fact> sink) {
        SourceSite site = session.site(unit, expression);
        try {
            NameCategory category = expressionCategory(expression, session);
            if (category == NameCategory.PACKAGE_NAME) return;
            if (category == NameCategory.TYPE_NAME) {
                emit(session, Kind.REFERENCE, site, session.target(JavaParserFactory.getContext(expression, session)
                        .solveType(NameLogic.nameAsString(expression)).getCorrespondingDeclaration()), sink);
                return;
            }
            // Local variables/parameters are not declaration endpoints in the published syntax model.
            ResolvedValueDeclaration declaration = resolver.get();
            if (declaration.isField() || declaration.isEnumConstant())
                emit(session, Kind.REFERENCE, site, session.target(declaration), sink);
        } catch (MethodAmbiguityException ambiguous) {
            unresolved(session, Kind.REFERENCE, site, Reason.AMBIGUOUS_TARGET, sink);
        } catch (UnsolvedSymbolException missing) {
            unresolved(session, Kind.REFERENCE, site, missingReason(missing), sink);
        } catch (UnresolvedRelationException uncertain) {
            unresolved(session, Kind.REFERENCE, site, uncertain.reason(), sink);
        } catch (UnsupportedOperationException unsupported) {
            unresolved(session, Kind.REFERENCE, site, Reason.UNSUPPORTED_CONSTRUCT, sink);
        }
    }

    private static Kind typeKind(ClassOrInterfaceType type) {
        Node parent = type.getParentNode().orElseThrow();
        if (parent instanceof ClassOrInterfaceDeclaration declaration) {
            if (declaration.getExtendedTypes().stream().anyMatch(value -> value == type)) return Kind.EXTENDS;
            if (declaration.getImplementedTypes().stream().anyMatch(value -> value == type)) return Kind.IMPLEMENTS;
        }
        if (parent instanceof EnumDeclaration declaration
                && declaration.getImplementedTypes().stream().anyMatch(value -> value == type)) return Kind.IMPLEMENTS;
        if (parent instanceof RecordDeclaration declaration
                && declaration.getImplementedTypes().stream().anyMatch(value -> value == type)) return Kind.IMPLEMENTS;
        return Kind.REFERENCE;
    }

    private static void resolve(Kind kind, ParsedJavaUnit unit, Node node, SourceResolutionSession session,
            Supplier<? extends ResolvedDeclaration> resolver, Consumer<Fact> sink) {
        SourceSite source = session.site(unit, node);
        try {
            emit(session, kind, source, session.target(resolver.get()), sink);
        } catch (MethodAmbiguityException ambiguous) {
            unresolved(session, kind, source, Reason.AMBIGUOUS_TARGET, sink);
        } catch (UnsolvedSymbolException missing) {
            unresolved(session, kind, source, missingReason(missing), sink);
        } catch (UnresolvedRelationException uncertain) {
            unresolved(session, kind, source, uncertain.reason(), sink);
        } catch (UnsupportedOperationException unsupported) {
            unresolved(session, kind, source, Reason.UNSUPPORTED_CONSTRUCT, sink);
        }
    }

    private static void overrides(ParsedJavaUnit unit, MethodDeclaration method,
            SourceResolutionSession session, Consumer<Fact> sink) {
        SourceSite source = session.site(unit, method.getName());
        try {
            ResolvedMethodDeclaration own = method.resolve();
            MethodUsage current = new MethodUsage(own);
            for (ResolvedReferenceType ancestor : own.declaringType().getAllAncestors()) {
                for (MethodUsage inherited : ancestor.getDeclaredMethods()) {
                    ResolvedMethodDeclaration target = inherited.getDeclaration();
                    if (target.isStatic() || target.accessSpecifier() == AccessSpecifier.PRIVATE
                            || target.accessSpecifier() == AccessSpecifier.NONE && !target.getPackageName().equals(own.getPackageName())) continue;
                    for (int parameter = 0; parameter < inherited.getNoParams(); parameter++) {
                        inherited = inherited.replaceParamType(parameter,
                                ancestor.typeParametersMap().replaceAll(inherited.getParamType(parameter)));
                    }
                    inherited = inherited.replaceReturnType(ancestor.typeParametersMap().replaceAll(inherited.returnType()));
                    if (current.isSubSignature(inherited) && current.isReturnTypeSubstituable(inherited)) {
                        emit(session, Kind.OVERRIDES, source, session.target(target), sink);
                    }
                }
            }
        } catch (MethodAmbiguityException ambiguous) {
            unresolved(session, Kind.OVERRIDES, source, Reason.AMBIGUOUS_TARGET, sink);
        } catch (UnsolvedSymbolException missing) {
            unresolved(session, Kind.OVERRIDES, source, missingReason(missing), sink);
        } catch (UnresolvedRelationException uncertain) {
            unresolved(session, Kind.OVERRIDES, source, uncertain.reason(), sink);
        } catch (UnsupportedOperationException unsupported) {
            unresolved(session, Kind.OVERRIDES, source, Reason.UNSUPPORTED_CONSTRUCT, sink);
        }
    }

    private static Reason missingReason(UnsolvedSymbolException missing) {
        Throwable cause = missing.getCause();
        while (Objects.nonNull(cause)) {
            if (cause instanceof UnresolvedRelationException uncertain) return uncertain.reason();
            cause = cause.getCause();
        }
        return Reason.MISSING_TYPE;
    }

    private static void emit(SourceResolutionSession session, Kind kind, SourceSite source, Target target, Consumer<Fact> sink) {
        Optional<Target> endpoint = Optional.of(target);
        sink.accept(new Fact(RelationContract.factIdentity(session.generation(), kind, source, endpoint), kind, source,
                ResolutionState.STATIC_RESOLVED, endpoint, Optional.empty(), Optional.empty()));
    }

    private static void unresolved(SourceResolutionSession session, Kind kind, SourceSite source, Reason reason, Consumer<Fact> sink) {
        sink.accept(new Fact(RelationContract.factIdentity(session.generation(), kind, source, Optional.empty()), kind, source,
                ResolutionState.UNRESOLVED, Optional.empty(), Optional.of(reason), Optional.empty()));
    }
}
