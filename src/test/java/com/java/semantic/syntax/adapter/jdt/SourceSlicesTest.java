package com.java.semantic.syntax.adapter.jdt;

import com.java.semantic.syntax.domain.SyntaxPosition;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SourceSlicesTest {

    @ParameterizedTest
    @ValueSource(strings = {"\r", "\r\n"})
    void should_treat_cr_line_endings_as_one_terminator_at_eof(String lineEnding) {
        String source = "class A {" + lineEnding + "}";
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(source.toCharArray());
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);
        TypeDeclaration type = (TypeDeclaration) unit.types().getFirst();

        SyntaxPosition end = new SourceSlices(unit, source).range(type).end();

        assertThat(end).isEqualTo(new SyntaxPosition(1, 1));
    }
}
