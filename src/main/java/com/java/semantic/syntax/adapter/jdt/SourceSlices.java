package com.java.semantic.syntax.adapter.jdt;

import com.java.semantic.syntax.domain.SourceSlice;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;

/** 依 AST offset 從同一份原始碼快照複製精確文字與座標 */
final class SourceSlices {

    private final CompilationUnit unit;

    private final String source;

    SourceSlices(CompilationUnit unit, String source) {
        this.unit = unit;
        this.source = source;
    }

    SourceSlice slice(ASTNode node) {
        int start = node.getStartPosition();
        int end = start + node.getLength();
        return new SourceSlice(range(start, end), source.substring(start, end));
    }

    SyntaxRange range(ASTNode node) {
        int start = node.getStartPosition();
        return range(start, start + node.getLength());
    }

    private SyntaxRange range(int start, int end) {
        return new SyntaxRange(position(start), position(end));
    }

    private SyntaxPosition position(int offset) {
        int oneBasedLine = unit.getLineNumber(offset);
        if (oneBasedLine >= 1) {
            return new SyntaxPosition(oneBasedLine - 1, unit.getColumnNumber(offset));
        }
        int line = 0;
        int lineStart = 0;
        for (int index = 0; index < offset; index++) {
            char current = source.charAt(index);
            if (current == '\r') {
                line++;
                lineStart = index + 1;
            } else if (current == '\n') {
                if (index == 0 || source.charAt(index - 1) != '\r') {
                    line++;
                }
                lineStart = index + 1;
            }
        }
        return new SyntaxPosition(line, offset - lineStart);
    }
}
