package com.java.semantic.indexer.index;

import com.github.javaparser.ast.CompilationUnit;
import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.support.ModelValidation;
import java.util.Objects;
import java.util.Optional;

/** One parse and one raw-coordinate mapping shared by syntax and relation extraction. */
public record ParsedJavaUnit(IndexContract.ParsedFile syntax, Optional<CompilationUnit> ast,
        Optional<RawJavaSourceMap> coordinates) {
    public ParsedJavaUnit {
        syntax = Objects.requireNonNull(syntax, "syntax evidence");
        ast = Objects.requireNonNull(ast, "parsed AST");
        coordinates = Objects.requireNonNull(coordinates, "raw coordinates");
        ModelValidation.require(ast.isPresent() == coordinates.isPresent()
                && ast.isPresent() == (syntax.file().status() == IndexContract.ParseStatus.PARSED),
                "only successfully parsed units retain AST and coordinates");
    }

    static ParsedJavaUnit unparsed(IndexContract.ParsedFile syntax) {
        return new ParsedJavaUnit(syntax, Optional.empty(), Optional.empty());
    }
}
