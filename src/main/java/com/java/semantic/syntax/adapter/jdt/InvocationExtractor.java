package com.java.semantic.syntax.adapter.jdt;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.java.semantic.syntax.domain.SyntaxInvocation;
import com.java.semantic.syntax.domain.SyntaxInvocation.InvocationKind;
import com.java.semantic.syntax.domain.InvocationTarget;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.identity.JavaIdentityNormalizer;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.CreationReference;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.MethodReference;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.TypeMethodReference;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.springframework.util.StringUtils;

/** 以型別化 AST 一次抽取方法內所有呼叫位置 */
final class InvocationExtractor {

    private static final String QUALIFIER = "Qualifier";

    private InvocationExtractor() {
    }

    static List<SyntaxInvocation> extract(CompilationUnit unit, ASTNode method, SourceSlices slices) {
        List<SyntaxInvocation> invocations = new ArrayList<>();
        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                Expression receiver = node.getExpression();
                IMethodBinding binding = node.resolveMethodBinding();
                InvocationKind kind = invocationKind(unit, binding);
                invocations.add(invocation(kind, node, receiver, unit, slices));
                return true;
            }

            @Override
            public boolean visit(ClassInstanceCreation node) {
                invocations.add(invocation(InvocationKind.CONSTRUCTOR, node, node.getType(),
                        unit, slices));
                return true;
            }

            @Override
            public boolean visit(CreationReference node) {
                invocations.add(methodReference(node, node.getType(), unit, slices));
                return true;
            }

            @Override
            public boolean visit(ExpressionMethodReference node) {
                invocations.add(methodReference(node, node.getExpression(), unit, slices));
                return true;
            }

            @Override
            public boolean visit(TypeMethodReference node) {
                invocations.add(methodReference(node, node.getType(), unit, slices));
                return true;
            }

            @Override
            public boolean visit(SuperMethodReference node) {
                invocations.add(superMethodReference(node, unit, slices));
                return true;
            }
        });
        return invocations.stream()
                .sorted(Comparator.comparingInt((SyntaxInvocation invocation) -> invocation.range().start().line())
                        .thenComparingInt(invocation -> invocation.range().start().character())
                        .thenComparing(SyntaxInvocation::kind))
                .toList();
    }

    private static SyntaxInvocation methodReference(MethodReference node, ASTNode receiver,
            CompilationUnit unit, SourceSlices slices) {
        return invocation(InvocationKind.METHOD_REFERENCE, node, receiver, unit, slices);
    }

    private static SyntaxInvocation superMethodReference(
            SuperMethodReference node, CompilationUnit unit, SourceSlices slices) {
        Name qualifier = node.getQualifier();
        String receiver = Objects.isNull(qualifier)
                ? "super"
                : slices.slice(qualifier).text() + ".super";
        IMethodBinding method = node.resolveMethodBinding();
        String declaration = validMethod(method)
                ? canonicalName(method.getMethodDeclaration().getDeclaringClass())
                : "";
        return new SyntaxInvocation(
                InvocationKind.METHOD_REFERENCE,
                slices.range(node),
                slices.slice(node).text(),
                receiver,
                declaration,
                "",
                invocationTarget(method),
                slices.range(node.getName()).start());
    }

    private static SyntaxInvocation invocation(InvocationKind kind, ASTNode node, ASTNode receiver,
            CompilationUnit unit, SourceSlices slices) {
        String receiverText = Objects.isNull(receiver) ? "" : slices.slice(receiver).text();
        String receiverDeclaration = resolvedReceiverType(receiver);
        String qualifier = receiver instanceof Expression expression ? qualifierOf(expression, unit) : "";
        return new SyntaxInvocation(
                kind,
                slices.range(node),
                slices.slice(node).text(),
                receiverText,
                receiverDeclaration,
                qualifier,
                invocationTarget(node),
                resolutionAnchor(node, slices));
    }

    private static SyntaxPosition resolutionAnchor(ASTNode node, SourceSlices slices) {
        ASTNode anchor = switch (node) {
            case MethodInvocation invocation -> invocation.getName();
            case ClassInstanceCreation creation -> creation.getType();
            case ExpressionMethodReference reference -> reference.getName();
            case TypeMethodReference reference -> reference.getName();
            case SuperMethodReference reference -> reference.getName();
            case CreationReference reference -> reference.getType();
            default -> node;
        };
        return slices.range(anchor).start();
    }

    private static Optional<InvocationTarget> invocationTarget(ASTNode node) {
        IMethodBinding binding = switch (node) {
            case MethodInvocation invocation -> invocation.resolveMethodBinding();
            case ClassInstanceCreation creation -> creation.resolveConstructorBinding();
            case MethodReference reference -> reference.resolveMethodBinding();
            default -> null;
        };
        return invocationTarget(binding);
    }

    private static Optional<InvocationTarget> invocationTarget(IMethodBinding binding) {
        if (!validMethod(binding)) {
            return Optional.empty();
        }
        IMethodBinding declaration = binding.getMethodDeclaration();
        ITypeBinding declaring = declaration.getDeclaringClass();
        if (Objects.isNull(declaring) || declaring.isRecovered()) {
            return Optional.empty();
        }
        ITypeBinding type = declaring.getTypeDeclaration();
        String packageName = type.getPackage().getName();
        String qualifiedName = type.getQualifiedName();
        if (!StringUtils.hasText(qualifiedName)) {
            return Optional.empty();
        }
        List<String> parameters = new ArrayList<>();
        for (ITypeBinding parameter : declaration.getParameterTypes()) {
            String name = canonicalName(parameter);
            if (!StringUtils.hasText(name)) {
                return Optional.empty();
            }
            parameters.add(name);
        }
        return Optional.of(new InvocationTarget(
                packageName, JavaIdentityNormalizer.className(packageName, qualifiedName), declaration.getName(), parameters));
    }

    private static String resolvedReceiverType(ASTNode receiver) {
        if (Objects.isNull(receiver)) {
            return "";
        }
        ITypeBinding binding;
        if (receiver instanceof Expression expression) {
            binding = expression.resolveTypeBinding();
        } else if (receiver instanceof Type type) {
            binding = type.resolveBinding();
        } else {
            return "";
        }
        if (Objects.isNull(binding)) {
            return "";
        }
        return canonicalName(binding);
    }

    private static String qualifierOf(Expression receiver, CompilationUnit unit) {
        Optional<IVariableBinding> variable = receiverVariable(receiver);
        if (!variable.isPresent()) {
            return "";
        }
        ASTNode declaration = unit.findDeclaringNode(variable.get().getVariableDeclaration());
        if (!(declaration instanceof VariableDeclarationFragment fragment)
                || !(fragment.getParent() instanceof FieldDeclaration field)) {
            return "";
        }
        return AnnotationReader.find(field, QUALIFIER)
                .flatMap(annotation -> AnnotationReader.stringValue(annotation, "value"))
                .orElse("");
    }

    private static Optional<IVariableBinding> receiverVariable(Expression receiver) {
        if (receiver instanceof SimpleName simple) {
            return variableBinding(simple.resolveBinding());
        }
        if (receiver instanceof FieldAccess access) {
            return variableBinding(access.getName().resolveBinding());
        }
        if (receiver instanceof QualifiedName qualified) {
            return variableBinding(qualified.getName().resolveBinding());
        }
        return Optional.empty();
    }

    private static Optional<IVariableBinding> variableBinding(IBinding binding) {
        return binding instanceof IVariableBinding variable ? Optional.of(variable) : Optional.empty();
    }

    static InvocationKind invocationKind(CompilationUnit unit, IMethodBinding method) {
        return isStaticImport(unit, method) ? InvocationKind.STATIC_IMPORT : InvocationKind.METHOD;
    }

    static boolean isStaticImport(CompilationUnit unit, IMethodBinding method) {
        if (!validMethod(method) || !Modifier.isStatic(method.getModifiers())) {
            return false;
        }
        IMethodBinding methodDeclaration = method.getMethodDeclaration();
        ITypeBinding declaringClass = methodDeclaration.getDeclaringClass();
        if (Objects.isNull(declaringClass) || declaringClass.isRecovered()) {
            return false;
        }
        for (Object value : unit.imports()) {
            ImportDeclaration declaration = (ImportDeclaration) value;
            if (!declaration.isStatic()) {
                continue;
            }
            IBinding imported = declaration.resolveBinding();
            if (imported instanceof IMethodBinding importedMethod && validMethod(importedMethod)
                    && importedMethod.getMethodDeclaration().isEqualTo(methodDeclaration)) {
                return true;
            }
            if (declaration.isOnDemand() && imported instanceof ITypeBinding importedType
                    && !importedType.isRecovered()) {
                String importedName = canonicalName(importedType);
                String declaringName = canonicalName(declaringClass);
                if (StringUtils.hasText(importedName) && importedName.equals(declaringName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean validMethod(IMethodBinding method) {
        if (Objects.isNull(method) || method.isRecovered()) {
            return false;
        }
        IMethodBinding declaration = method.getMethodDeclaration();
        return Objects.nonNull(declaration) && !declaration.isRecovered();
    }

    private static String canonicalName(ITypeBinding binding) {
        if (Objects.isNull(binding) || binding.isRecovered()) {
            return "";
        }
        ITypeBinding declaration = binding.getTypeDeclaration();
        if (declaration.isRecovered()) {
            return "";
        }
        String qualifiedName = declaration.getQualifiedName();
        return StringUtils.hasText(qualifiedName) ? qualifiedName : "";
    }
}
