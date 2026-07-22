package com.java.semantic.syntax.adapter.jdt;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.syntax.domain.MethodTargetResolution;

import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.springframework.util.StringUtils;

/** Resolves declaration identities solely from canonical JDT bindings. */
final class JdtCanonicalMethodTargetResolver {

    private static final String PARAMETER_BINDING_UNRESOLVED = "METHOD_PARAMETER_BINDING_UNRESOLVED";

    MethodTargetResolution resolve(
            SourceFile source,
            String packageName,
            String nestedClassName,
            MethodDeclaration method) {
        IMethodBinding binding = method.resolveBinding();
        if (Objects.isNull(binding) || binding.isRecovered()) {
            return MethodTargetResolution.unresolved(PARAMETER_BINDING_UNRESOLVED);
        }
        IMethodBinding declaration = binding.getMethodDeclaration();
        if (Objects.isNull(declaration) || declaration.isRecovered()) {
            return MethodTargetResolution.unresolved(PARAMETER_BINDING_UNRESOLVED);
        }
        List<String> parameters = new ArrayList<>();
        for (ITypeBinding parameter : declaration.getParameterTypes()) {
            Optional<String> canonical = canonicalParameterType(parameter, declaration);
            if (canonical.isEmpty()) {
                return MethodTargetResolution.unresolved(PARAMETER_BINDING_UNRESOLVED);
            }
            parameters.add(canonical.get());
        }
        return MethodTargetResolution.resolved(new MethodTarget(
                source.repositoryRelativePath(),
                packageName,
                nestedClassName,
                declaration.getName(),
                parameters));
    }

    private Optional<String> canonicalParameterType(ITypeBinding parameter, IMethodBinding declaration) {
        if (Objects.isNull(parameter) || parameter.isRecovered()) {
            return Optional.empty();
        }
        int dimensions = parameter.getDimensions();
        ITypeBinding element = parameter.isArray() ? parameter.getElementType() : parameter;
        if (Objects.isNull(element) || element.isRecovered() || element.isWildcardType()
                || element.isCapture() || element.isIntersectionType()) {
            return Optional.empty();
        }
        String baseName = canonicalElementName(element, declaration);
        if (!StringUtils.hasText(baseName)) {
            return Optional.empty();
        }
        return Optional.of(baseName + "[]".repeat(dimensions));
    }

    private String canonicalElementName(ITypeBinding element, IMethodBinding declaration) {
        if (element.isTypeVariable()) {
            IMethodBinding declaringMethod = element.getDeclaringMethod();
            if (Objects.nonNull(declaringMethod)) {
                if (declaringMethod.isRecovered()) {
                    return "";
                }
                IMethodBinding declaringDeclaration = declaringMethod.getMethodDeclaration();
                if (Objects.isNull(declaringDeclaration) || declaringDeclaration.isRecovered()) {
                    return "";
                }
                if (declaringDeclaration.isEqualTo(declaration)) {
                    return element.getName();
                }
            }
        }
        ITypeBinding erased = element.getErasure();
        if (Objects.isNull(erased) || erased.isRecovered() || erased.isWildcardType()
                || erased.isCapture() || erased.isIntersectionType()) {
            return "";
        }
        if (erased.isPrimitive()) {
            return StringUtils.hasText(erased.getName()) ? erased.getName() : "";
        }
        String qualifiedName = erased.getQualifiedName();
        return StringUtils.hasText(qualifiedName) ? qualifiedName.replace('$', '.') : "";
    }
}
