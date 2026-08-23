package com.java.semantic.syntax.adapter.jdt;
import com.java.semantic.model.codefact.SourceRange;

import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.syntax.application.SourceSymbolContext;
import com.java.semantic.syntax.domain.ExactSourceDeclarationTarget;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.SourceMemberIdentity;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 供 symbol search 與 exact lookup 共用的單一 AST declaration matcher */
final class JdtSourceDeclarationLocator {

    private final JdtCanonicalMethodTargetResolver methodTargetResolver = new JdtCanonicalMethodTargetResolver();

    List<LocatedContext> contexts(ParsedSource parsed, SourceSymbolContext requested) {
        List<LocatedContext> contexts = new ArrayList<>();
        for (AbstractTypeDeclaration type : SourceTypes.allTypesOf(parsed.unit())) {
            if (type instanceof AnnotationTypeDeclaration
                    || !requested.javaType().fullyQualifiedName().equals(fullyQualifiedName(parsed, type))) {
                continue;
            }
            if (requested.method().isEmpty()) {
                contexts.add(new LocatedContext(parsed, type, Optional.empty(), Optional.empty()));
                continue;
            }
            SourceSymbolContext.MethodContext requestedMethod = requested.method().orElseThrow();
            for (MethodDeclaration method : SourceTypes.declaredMethodsOf(type)) {
                Optional<MethodTarget> target = methodTarget(parsed, type, method).target();
                if (!requestedMethod.name().equals(method.getName().getIdentifier()) || target.isEmpty()) {
                    continue;
                }
                if (requestedMethod.parameterTypes().isPresent()
                        && !requestedMethod.parameterTypes().orElseThrow().equals(target.orElseThrow().parameterTypes())) {
                    continue;
                }
                contexts.add(new LocatedContext(parsed, type, Optional.of(method), target));
            }
        }
        return List.copyOf(contexts);
    }

    List<LocatedDeclaration> directDeclarations(AbstractTypeDeclaration type, String symbol) {
        List<LocatedDeclaration> declarations = new ArrayList<>();
        for (Object member : type.bodyDeclarations()) {
            if (member instanceof FieldDeclaration field) {
                for (Object fragmentValue : field.fragments()) {
                    VariableDeclarationFragment fragment = (VariableDeclarationFragment) fragmentValue;
                    addWhenMatching(declarations, symbol, fragment.getName(), field, fragment);
                }
            } else if (member instanceof MethodDeclaration method) {
                addWhenMatching(declarations, symbol, method.getName(), method, method);
            } else if (member instanceof AbstractTypeDeclaration nested) {
                addWhenMatching(declarations, symbol, nested.getName(), nested, nested);
            }
        }
        if (type instanceof EnumDeclaration enumeration) {
            for (Object constantValue : enumeration.enumConstants()) {
                EnumConstantDeclaration constant = (EnumConstantDeclaration) constantValue;
                addWhenMatching(declarations, symbol, constant.getName(), constant, constant);
            }
        }
        if (type instanceof RecordDeclaration record) {
            for (Object componentValue : record.recordComponents()) {
                SingleVariableDeclaration component = (SingleVariableDeclaration) componentValue;
                addWhenMatching(declarations, symbol, component.getName(), component, component);
            }
        }
        declarations.sort(Comparator.comparingInt(declaration -> declaration.name().getStartPosition()));
        return List.copyOf(declarations);
    }

    Optional<LocatedDeclaration> exact(ParsedSource parsed, ExactSourceDeclarationTarget target) {
        return switch (target) {
            case ExactSourceDeclarationTarget.Type type -> typeDeclaration(parsed, type.identity())
                    .map(declaration -> new LocatedDeclaration(
                            declaration.getName(), declaration, declaration));
            case ExactSourceDeclarationTarget.Method method -> methodDeclaration(parsed, method.identity())
                    .map(declaration -> new LocatedDeclaration(
                            declaration.getName(), declaration, declaration));
            case ExactSourceDeclarationTarget.Member member -> memberDeclaration(parsed, member.identity());
        };
    }

    MethodTargetResolution methodTarget(
            ParsedSource parsed,
            AbstractTypeDeclaration ownerType,
            MethodDeclaration method) {
        return methodTargetResolver.resolve(
                parsed.source(), packageName(parsed), SourceTypes.nestedName(ownerType), method);
    }

    Optional<BindingIdentity> bindingIdentity(IBinding binding) {
        if (Objects.isNull(binding) || binding.isRecovered()) {
            return Optional.empty();
        }
        if (binding instanceof ITypeBinding type && type.isTypeVariable()) {
            return Optional.of(new BindingIdentity("unsupported", true));
        }
        IBinding declaration = binding;
        if (binding instanceof IVariableBinding variable) {
            declaration = variable.getVariableDeclaration();
        } else if (binding instanceof IMethodBinding method) {
            declaration = method.getMethodDeclaration();
        } else if (binding instanceof ITypeBinding type) {
            declaration = type.getTypeDeclaration();
        }
        if (Objects.isNull(declaration) || declaration.isRecovered() || !StringUtils.hasText(declaration.getKey())) {
            return Optional.empty();
        }
        return Optional.of(new BindingIdentity(declaration.getKey(), false));
    }

    SyntaxRange range(ParsedSource parsed, ASTNode node) {
        return AstSourceRanges.range(parsed.unit(), node);
    }

    String packageName(ParsedSource parsed) {
        return Objects.nonNull(parsed.unit().getPackage())
                ? parsed.unit().getPackage().getName().getFullyQualifiedName()
                : "";
    }

    String fullyQualifiedName(ParsedSource parsed, AbstractTypeDeclaration type) {
        String nestedName = SourceTypes.nestedName(type);
        String packageName = packageName(parsed);
        return packageName.isEmpty() ? nestedName : packageName + "." + nestedName;
    }

    boolean contains(ParsedSource parsed, ASTNode node, SyntaxPosition position) {
        SyntaxRange range = range(parsed, node);
        return compare(position, range.start()) >= 0 && compare(position, range.end()) < 0;
    }

    ASTNode declarationRangeNode(SimpleName name) {
        ASTNode parent = name.getParent();
        if (parent instanceof VariableDeclarationFragment fragment
                && fragment.getParent() instanceof FieldDeclaration field) {
            return field;
        }
        if (parent instanceof VariableDeclarationFragment fragment
                && fragment.getParent() instanceof VariableDeclarationStatement statement) {
            return statement;
        }
        return parent;
    }

    private Optional<AbstractTypeDeclaration> typeDeclaration(ParsedSource parsed, SourceTypeIdentity identity) {
        return SourceTypes.allTypesOf(parsed.unit()).stream()
                .filter(type -> identity.fullyQualifiedName().equals(fullyQualifiedName(parsed, type)))
                .findFirst();
    }

    private Optional<MethodDeclaration> methodDeclaration(ParsedSource parsed, MethodTarget target) {
        return typeDeclaration(parsed, target.sourceType()).stream()
                .flatMap(type -> SourceTypes.declaredMethodsOf(type).stream()
                        .filter(method -> methodTarget(parsed, type, method).target().filter(target::equals).isPresent()))
                .findFirst();
    }

    private Optional<LocatedDeclaration> memberDeclaration(ParsedSource parsed, SourceMemberIdentity identity) {
        return switch (identity) {
            case SourceMemberIdentity.TypeMember member -> typeDeclaration(parsed, member.ownerType()).stream()
                    .flatMap(type -> directDeclarations(type, member.name()).stream())
                    .filter(declaration -> !(declaration.identityNode() instanceof MethodDeclaration))
                    .filter(declaration -> !(declaration.identityNode() instanceof AbstractTypeDeclaration))
                    .findFirst();
            case SourceMemberIdentity.MethodScoped member -> methodDeclaration(parsed, member.declaringMethod())
                    .flatMap(method -> methodScopedDeclaration(parsed, method, member));
        };
    }

    private Optional<LocatedDeclaration> methodScopedDeclaration(
            ParsedSource parsed,
            MethodDeclaration method,
            SourceMemberIdentity.MethodScoped identity) {
        List<LocatedDeclaration> matches = new ArrayList<>();
        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName name) {
                if (name.isDeclaration()
                        && identity.name().equals(name.getIdentifier())
                        && identity.declarationRange().equals(range(parsed, name))) {
                    matches.add(new LocatedDeclaration(name, name, name.getParent()));
                }
                return true;
            }
        });
        return matches.stream().findFirst();
    }

    private void addWhenMatching(
            List<LocatedDeclaration> declarations,
            String symbol,
            SimpleName name,
            ASTNode rangeNode,
            ASTNode identityNode) {
        if (symbol.equals(name.getIdentifier())) {
            declarations.add(new LocatedDeclaration(name, rangeNode, identityNode));
        }
    }

    private int compare(SyntaxPosition left, SyntaxPosition right) {
        int line = Integer.compare(left.line(), right.line());
        return line != 0 ? line : Integer.compare(left.character(), right.character());
    }

    record LocatedContext(
            ParsedSource parsed,
            AbstractTypeDeclaration type,
            Optional<MethodDeclaration> method,
            Optional<MethodTarget> target) {
    }

    record LocatedDeclaration(SimpleName name, ASTNode rangeNode, ASTNode identityNode) {
    }

    record BindingIdentity(String key, boolean unsupported) {
    }
}
