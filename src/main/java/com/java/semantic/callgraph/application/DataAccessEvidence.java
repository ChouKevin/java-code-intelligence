package com.java.semantic.callgraph.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.syntax.domain.AnnotationEvidence;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SourceTypeKind;
import com.java.semantic.syntax.domain.SourceTypeMetadata;

/**
 * 偵測介面宣告的方法是否為資料存取進入點
 * <p>
 * 規則依序評估、第一個命中即回傳：MyBatis mapper SQL、Spring Data repository 已知父介面、
 * 帶有 {@code @Mapper}/{@code @Repository} 但缺乏具體證據的介面方法
 * <p>
 * 每條規則皆為 fail-closed：僅 {@link SourceTypeKind#INTERFACE} 宣告型別參與評估，任何無法明確
 * 判定的情況一律回傳空值，交由呼叫端視為非資料存取進入點
 * <p>
 * 純元件，不做任何 I/O 或記錄
 */
public final class DataAccessEvidence {

    private static final Set<String> SPRING_DATA_SUPERTYPES = Set.of(
            "JpaRepository", "CrudRepository", "PagingAndSortingRepository", "MongoRepository",
            "R2dbcRepository", "ListCrudRepository", "ReactiveCrudRepository");

    private static final Set<String> DATA_ACCESS_ANNOTATIONS = Set.of("Mapper", "Repository");

    /**
     * 評估一個宣告方法是否對應資料存取規則
     *
     * @param declaringType     宣告該方法的型別 metadata
     * @param declaredMethod    方法簽名
     * @param declarationTarget 該宣告對應的原始碼位置
     * @return 命中的證據，未命中任何規則時為空
     */
    Optional<EvidenceMatch> evaluate(
            SourceTypeMetadata declaringType, SourceMethodMetadata declaredMethod, MethodTarget declarationTarget) {
        return evaluate(
                new RepositorySyntaxIndex(
                        "evidence", new com.java.semantic.syntax.domain.RepositorySyntax(List.of(), List.of(declaringType))),
                declaringType,
                declaredMethod,
                declarationTarget);
    }

    Optional<EvidenceMatch> evaluate(
            RepositorySyntaxIndex index,
            SourceTypeMetadata declaringType,
            SourceMethodMetadata declaredMethod,
            MethodTarget declarationTarget) {
        Objects.requireNonNull(index, "index is required");
        Objects.requireNonNull(declaringType, "declaringType is required");
        Objects.requireNonNull(declaredMethod, "declaredMethod is required");
        Objects.requireNonNull(declarationTarget, "declarationTarget is required");

        if (declaringType.declaration().kind() != SourceTypeKind.INTERFACE) {
            return Optional.empty();
        }

        return matchMybatisMapper(index, declaringType, declaredMethod, declarationTarget)
                .or(() -> matchSpringDataRepository(declaringType, declaredMethod, declarationTarget))
                .or(() -> matchDataAccessWithoutEvidence(declaringType, declaredMethod, declarationTarget));
    }

    private Optional<EvidenceMatch> matchMybatisMapper(
            RepositorySyntaxIndex index,
            SourceTypeMetadata declaringType,
            SourceMethodMetadata declaredMethod,
            MethodTarget declarationTarget) {
        if (Objects.isNull(declaredMethod.sqlSource())) {
            return Optional.empty();
        }
        return Optional.of(matched(
                ResolutionStrategy.MYBATIS_MAPPER, declaringType, declaredMethod, declarationTarget,
                List.of("mapper SQL evidence: " + declaredMethod.sqlSource().name()),
                index.mapperStatementIdentities(
                        declaringType.declaration().identity().fullyQualifiedName(), declaredMethod.name())));
    }

    /**
     * 介面的 {@code extends} 子句保留在直接關係 evidence 中，
     * 一般型別的父類別則落在 extendedTypes；此規則已由 {@link #evaluate} 限定僅介面參與，
     * 因此取兩者聯集比對已知父介面不會誤判類別
     */
    private Optional<EvidenceMatch> matchSpringDataRepository(
            SourceTypeMetadata declaringType, SourceMethodMetadata declaredMethod, MethodTarget declarationTarget) {
        return Stream.concat(declaringType.relationships().extendedTypes().stream(),
                        declaringType.relationships().implementedTypes().stream())
                .map(reference -> reference.simpleTypeName())
                .filter(SPRING_DATA_SUPERTYPES::contains)
                .findFirst()
                .map(supertype -> matched(
                        ResolutionStrategy.SPRING_DATA_REPOSITORY, declaringType, declaredMethod, declarationTarget,
                        List.of("extends " + supertype), List.of()));
    }

    private Optional<EvidenceMatch> matchDataAccessWithoutEvidence(
            SourceTypeMetadata declaringType, SourceMethodMetadata declaredMethod, MethodTarget declarationTarget) {
        return declaringType.frameworkFacts().annotations().stream()
                .map(AnnotationEvidence::writtenName)
                .map(AnnotationSimpleNames::simpleName)
                .filter(DATA_ACCESS_ANNOTATIONS::contains)
                .findFirst()
                .map(annotation -> matched(
                        ResolutionStrategy.DATA_ACCESS_WITHOUT_EVIDENCE, declaringType, declaredMethod,
                        declarationTarget,
                        List.of("@" + annotation + " without SQL or known supertype"), List.of()));
    }

    private static EvidenceMatch matched(
            ResolutionStrategy strategy, SourceTypeMetadata declaringType, SourceMethodMetadata declaredMethod,
            MethodTarget declarationTarget,
            List<String> evidence,
            List<com.java.semantic.syntax.domain.MapperStatementIdentity> evidenceSourceIdentities) {
        return new EvidenceMatch(
                strategy, opaqueSymbolOf(declaringType, declaredMethod), Optional.of(declarationTarget),
                evidence, evidenceSourceIdentities);
    }

    private static String opaqueSymbolOf(SourceTypeMetadata declaringType, SourceMethodMetadata declaredMethod) {
        return declaringType.declaration().identity().fullyQualifiedName() + "#" + declaredMethod.name()
                + "(" + String.join(",", declaredMethod.paramTypes()) + ")";
    }
}
