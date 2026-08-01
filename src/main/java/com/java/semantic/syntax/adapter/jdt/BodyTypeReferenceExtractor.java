package com.java.semantic.syntax.adapter.jdt;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import com.java.semantic.identity.JavaIdentityNormalizer;
import com.java.semantic.identity.JavaTypeIdentity;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IPackageBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.InstanceofExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeLiteral;
import org.springframework.util.StringUtils;

/** 從方法本體與 annotation member 抽取 binding 已證實的型別 identity */
final class BodyTypeReferenceExtractor {

    private BodyTypeReferenceExtractor() {
    }

    static List<JavaTypeIdentity> extract(MethodDeclaration method) {
        Set<JavaTypeIdentity> references = new TreeSet<>(Comparator
                .comparing(JavaTypeIdentity::packageName)
                .thenComparing(JavaTypeIdentity::className));
        ASTVisitor visitor = visitor(references);
        ASTNode body = method.getBody();
        if (Objects.nonNull(body)) {
            body.accept(visitor);
        }
        for (Object modifier : method.modifiers()) {
            if (modifier instanceof Annotation annotation) {
                annotation.accept(visitor);
            }
        }
        return List.copyOf(references);
    }

    private static ASTVisitor visitor(Set<JavaTypeIdentity> references) {
        return new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                if (node instanceof Type type) {
                    addType(type.resolveBinding(), references);
                }
            }

            @Override
            public boolean visit(CastExpression node) {
                addType(node.getType().resolveBinding(), references);
                return true;
            }

            @Override
            public boolean visit(InstanceofExpression node) {
                addType(node.getRightOperand().resolveBinding(), references);
                return true;
            }

            @Override
            public boolean visit(TypeLiteral node) {
                addType(node.getType().resolveBinding(), references);
                return true;
            }

            @Override
            public boolean visit(FieldAccess node) {
                addField(node.resolveFieldBinding(), references);
                return true;
            }

            @Override
            public boolean visit(QualifiedName node) {
                addField(variableBinding(node.resolveBinding()), references);
                return true;
            }

            @Override
            public boolean visit(SimpleName node) {
                addField(variableBinding(node.resolveBinding()), references);
                return true;
            }
        };
    }

    private static IVariableBinding variableBinding(IBinding binding) {
        return binding instanceof IVariableBinding variable ? variable : null;
    }

    private static void addField(IVariableBinding field, Set<JavaTypeIdentity> references) {
        if (Objects.isNull(field) || !field.isField()) {
            return;
        }
        IVariableBinding declaration = field.getVariableDeclaration();
        addType(declaration.getDeclaringClass(), references);
        addType(declaration.getType(), references);
    }

    private static void addType(ITypeBinding binding, Set<JavaTypeIdentity> references) {
        addType(binding, references, new HashSet<>());
    }

    private static void addType(
            ITypeBinding binding,
            Set<JavaTypeIdentity> references,
            Set<String> visitingBindings) {
        if (Objects.isNull(binding)) {
            return;
        }
        String bindingKey = binding.getKey();
        boolean tracked = StringUtils.hasText(bindingKey);
        if (tracked && !visitingBindings.add(bindingKey)) {
            return;
        }
        try {
            addResolvedIdentity(binding, references);
            for (ITypeBinding argument : binding.getTypeArguments()) {
                addType(argument, references, visitingBindings);
            }
            if (binding.isArray()) {
                addType(binding.getElementType(), references, visitingBindings);
            }
            if (binding.isCapture()) {
                addType(binding.getWildcard(), references, visitingBindings);
            }
            if (binding.isWildcardType()) {
                addType(binding.getBound(), references, visitingBindings);
            }
            for (ITypeBinding bound : binding.getTypeBounds()) {
                addType(bound, references, visitingBindings);
            }
        } finally {
            if (tracked) {
                visitingBindings.remove(bindingKey);
            }
        }
    }

    private static void addResolvedIdentity(ITypeBinding binding, Set<JavaTypeIdentity> references) {
        ITypeBinding declaration = binding.getTypeDeclaration();
        if (Objects.isNull(declaration)) {
            return;
        }
        ITypeBinding sourceType = sourceTypeOf(declaration);
        if (sourceType.isRecovered()) {
            return;
        }
        String qualifiedName = sourceType.getQualifiedName();
        if (!StringUtils.hasText(qualifiedName)) {
            return;
        }
        IPackageBinding packageBinding = sourceType.getPackage();
        if (Objects.isNull(packageBinding)) {
            return;
        }
        String packageName = packageBinding.getName();
        references.add(new JavaTypeIdentity(
                packageName, JavaIdentityNormalizer.className(packageName, qualifiedName)));
    }

    private static ITypeBinding sourceTypeOf(ITypeBinding declaration) {
        return declaration.isArray() ? declaration.getElementType().getTypeDeclaration() : declaration;
    }
}
