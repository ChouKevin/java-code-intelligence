package com.java.semantic.syntax.adapter.jdt;

import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;

/** 將 JDT AST offset 轉為零基 UTF-16 半開位置 */
final class AstSourceRanges {

    private AstSourceRanges() {
    }

    static SyntaxRange range(CompilationUnit unit, ASTNode node) {
        int start = node.getStartPosition();
        return range(unit, start, start + node.getLength());
    }

    static SyntaxRange declarationRange(CompilationUnit unit, BodyDeclaration declaration) {
        SyntaxRange declarationRange = range(unit, declaration);
        if (declaration.getJavadoc() == null) { // cs-allow
            return declarationRange;
        }
        SyntaxRange javadocRange = range(unit, declaration.getJavadoc());
        return new SyntaxRange(javadocRange.start(), declarationRange.end());
    }

    static String text(String source, ASTNode node) {
        String requiredSource = java.util.Objects.requireNonNull(source, "source is required");
        int start = node.getStartPosition();
        return requiredSource.substring(start, start + node.getLength());
    }

    private static SyntaxRange range(CompilationUnit unit, int start, int end) {
        return new SyntaxRange(position(unit, start), position(unit, end));
    }

    private static SyntaxPosition position(CompilationUnit unit, int offset) {
        int oneBasedLine = unit.getLineNumber(offset);
        if (oneBasedLine < 1 && offset == unit.getLength() && offset > 0) {
            int finalCharacter = offset - 1;
            return new SyntaxPosition(
                    unit.getLineNumber(finalCharacter) - 1,
                    unit.getColumnNumber(finalCharacter) + 1);
        }
        if (oneBasedLine < 1) {
            throw new IllegalArgumentException("AST offset is outside compilation unit");
        }
        return new SyntaxPosition(oneBasedLine - 1, unit.getColumnNumber(offset));
    }
}
