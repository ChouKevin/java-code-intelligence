package com.java.semantic.syntax.adapter.jdt;

import com.java.semantic.syntax.domain.SyntaxPosition;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceSlicesTest {

    @Test
    void should_treat_a_lone_carriage_return_as_a_line_terminator_at_eof() {
        String source = "class A {\r}";
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(source.toCharArray());
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);
        TypeDeclaration type = (TypeDeclaration) unit.types().getFirst();

        SyntaxPosition end = new SourceSlices(unit, source).range(type).end();

        assertThat(end).isEqualTo(new SyntaxPosition(1, 1));
    }

    @Test
    void should_count_carriage_return_line_feed_as_one_terminator_at_eof() {
        String source = "class A {\r\n}";
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(source.toCharArray());
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);
        TypeDeclaration type = (TypeDeclaration) unit.types().getFirst();

        SyntaxPosition end = new SourceSlices(unit, source).range(type).end();

        assertThat(end).isEqualTo(new SyntaxPosition(1, 1));
    }
}
