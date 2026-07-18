package com.java.semantic.syntax.adapter.jdt;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.EntryPointMethod;

import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.springframework.util.CollectionUtils;

/**
 * 單一原始檔的語法抽取
 * <p>
 * 抽成獨立協作者，讓 repository 層的走訪只負責彙整與逐檔隔離，不必同時扛型別走訪規則
 */
class SourceSyntaxExtractor {

    private static final String DEPRECATED = "Deprecated";

    /** 一份編譯單元中的入口類別與型別 metadata */
    SourceSyntax extractFrom(ParsedSource parsed, MapperXmlSqlExtractor.SqlIndex sqlIndex) {
        List<AbstractTypeDeclaration> types = SourceTypes.allTypesOf(parsed.unit());
        boolean skipEntryPoints = types.stream().anyMatch(ApiExtractor::isControllerAdvice);

        List<EntryPointClass> entryPoints = new ArrayList<>();
        List<ClassMetadata> classes = new ArrayList<>();
        for (AbstractTypeDeclaration type : types) {
            if (!skipEntryPoints) {
                toEntryPointClass(parsed, type).ifPresent(entryPoints::add);
            }
            if (SourceTypes.isMetadataCandidate(type)) {
                classes.add(ClassMetadataExtractor.extract(parsed, type, sqlIndex));
            }
        }
        return new SourceSyntax(List.copyOf(entryPoints), List.copyOf(classes));
    }

    private Optional<EntryPointClass> toEntryPointClass(ParsedSource parsed, AbstractTypeDeclaration type) {
        if (AnnotationReader.isPresent(type, DEPRECATED)) {
            return Optional.empty();
        }

        List<EntryPointMethod> methods = new ArrayList<>();
        methods.addAll(ApiExtractor.extract(type));
        methods.addAll(MqExtractor.extract(type));
        methods.addAll(ScheduleExtractor.extract(type));

        if (CollectionUtils.isEmpty(methods)) {
            return Optional.empty();
        }

        return Optional.of(new EntryPointClass(
                SourceTypes.nestedName(type),
                PackageNames.of(parsed),
                parsed.source().relativePath(),
                JavadocReader.descriptionOf(type),
                ApiExtractor.basePathsOf(type),
                methods));
    }
}
