package com.java.semantic.syntax.adapter.jdt;

import java.lang.reflect.Modifier;
import java.util.List;

import com.java.semantic.syntax.domain.SyntaxInvocation;

import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class InvocationExtractorTest {

    @Test
    void should_classify_a_valid_explicit_static_import() {
        CompilationUnit unit = mock(CompilationUnit.class);
        ImportDeclaration importDeclaration = mock(ImportDeclaration.class);
        IMethodBinding method = mock(IMethodBinding.class);
        IMethodBinding methodDeclaration = mock(IMethodBinding.class);
        IMethodBinding importedMethod = mock(IMethodBinding.class);
        ITypeBinding declaringType = mock(ITypeBinding.class);
        given(unit.imports()).willReturn(List.of(importDeclaration));
        given(importDeclaration.isStatic()).willReturn(true);
        given(importDeclaration.isOnDemand()).willReturn(false);
        given(importDeclaration.resolveBinding()).willReturn(importedMethod);
        given(method.getModifiers()).willReturn(Modifier.STATIC);
        given(method.getMethodDeclaration()).willReturn(methodDeclaration);
        given(method.getDeclaringClass()).willReturn(declaringType);
        given(methodDeclaration.getDeclaringClass()).willReturn(declaringType);
        given(importedMethod.getMethodDeclaration()).willReturn(methodDeclaration);
        given(methodDeclaration.isEqualTo(methodDeclaration)).willReturn(true);

        assertThat(InvocationExtractor.invocationKind(unit, method))
                .isEqualTo(SyntaxInvocation.InvocationKind.STATIC_IMPORT);
        assertThat(InvocationExtractor.isStaticImport(unit, method)).isTrue();
    }

    @Test
    void should_not_classify_recovered_empty_canonical_names_as_the_same_static_import() throws Exception {
        CompilationUnit unit = mock(CompilationUnit.class);
        ImportDeclaration importDeclaration = mock(ImportDeclaration.class);
        IMethodBinding method = mock(IMethodBinding.class);
        IMethodBinding methodDeclaration = mock(IMethodBinding.class);
        ITypeBinding declaringType = recoveredType();
        ITypeBinding importedType = recoveredType();
        given(unit.imports()).willReturn(List.of(importDeclaration));
        given(importDeclaration.isStatic()).willReturn(true);
        given(importDeclaration.isOnDemand()).willReturn(true);
        given(importDeclaration.resolveBinding()).willReturn(importedType);
        given(method.getModifiers()).willReturn(Modifier.STATIC);
        given(method.getMethodDeclaration()).willReturn(methodDeclaration);
        given(method.getDeclaringClass()).willReturn(declaringType);
        given(methodDeclaration.getDeclaringClass()).willReturn(declaringType);

        boolean staticImport = InvocationExtractor.isStaticImport(unit, method);

        assertThat(staticImport).isFalse();
    }

    private ITypeBinding recoveredType() {
        ITypeBinding binding = mock(ITypeBinding.class);
        given(binding.getTypeDeclaration()).willReturn(binding);
        given(binding.isRecovered()).willReturn(true);
        return binding;
    }
}
