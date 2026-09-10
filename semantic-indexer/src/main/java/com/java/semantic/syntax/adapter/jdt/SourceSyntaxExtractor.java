package com.java.semantic.syntax.adapter.jdt;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.EntryPointMethod;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.SourceTypeIdentity;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
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
        IdentityHashMap<MethodDeclaration, MethodTargetResolution> targetCache = new IdentityHashMap<>();
        JdtCanonicalMethodTargetResolver resolver = new JdtCanonicalMethodTargetResolver();
        Function<MethodDeclaration, MethodTargetResolution> analysisTargetOf = method -> targetCache.computeIfAbsent(
                method,
                declaration -> resolver.resolve(
                        parsed.source(),
                        PackageNames.of(parsed),
                        SourceTypes.nestedName(enclosingTypeOf(declaration)),
                        declaration));
        List<AbstractTypeDeclaration> types = SourceTypes.allTypesOf(parsed.unit());
        boolean skipEntryPoints = types.stream().anyMatch(HttpEndpointExtractor::isControllerAdvice);

        List<EntryPointClass> entryPoints = new ArrayList<>();
        List<SourceTypeMetadata> sourceTypes = new ArrayList<>();
        for (AbstractTypeDeclaration type : types) {
            if (!skipEntryPoints) {
                toEntryPointClass(parsed, type, analysisTargetOf).ifPresent(entryPoints::add);
            }
            if (SourceTypes.isMetadataCandidate(type)) {
                sourceTypes.add(SourceTypeMetadataExtractor.extract(parsed, type, sqlIndex, analysisTargetOf));
            }
        }
        return new SourceSyntax(List.copyOf(entryPoints), List.copyOf(sourceTypes));
    }

    private Optional<EntryPointClass> toEntryPointClass(
            ParsedSource parsed,
            AbstractTypeDeclaration type,
            Function<MethodDeclaration, MethodTargetResolution> analysisTargetOf) {
        if (AnnotationReader.isPresent(type, DEPRECATED)) {
            return Optional.empty();
        }

        List<EntryPointMethod> methods = new ArrayList<>();
        methods.addAll(HttpEndpointExtractor.extract(type, analysisTargetOf));
        methods.addAll(MessageListenerExtractor.extract(type, analysisTargetOf));
        methods.addAll(ScheduleExtractor.extract(type, analysisTargetOf));

        if (CollectionUtils.isEmpty(methods)) {
            return Optional.empty();
        }

        return Optional.of(new EntryPointClass(
                new SourceTypeIdentity(
                        new JavaTypeIdentity(PackageNames.of(parsed), SourceTypes.nestedName(type)),
                        parsed.source().repositoryRelativePath()),
                JavadocReader.descriptionOf(type),
                HttpEndpointExtractor.basePathsOf(type),
                methods));
    }

    private AbstractTypeDeclaration enclosingTypeOf(MethodDeclaration method) {
        ASTNode current = method.getParent();
        while (Objects.nonNull(current)) {
            if (current instanceof AbstractTypeDeclaration type) {
                return type;
            }
            current = current.getParent();
        }
        throw new IllegalArgumentException("method declaration has no enclosing type");
    }
}
