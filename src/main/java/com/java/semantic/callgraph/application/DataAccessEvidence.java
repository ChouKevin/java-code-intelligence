package com.java.semantic.callgraph.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.ClassMetadata.MethodSignature;
import com.java.semantic.syntax.domain.ClassMetadata.TypeKind;

/**
 * 偵測介面宣告的方法是否為資料存取進入點
 * <p>
 * 規則依序評估、第一個命中即回傳：MyBatis mapper SQL、Spring Data repository 已知父介面、
 * 帶有 {@code @Mapper}/{@code @Repository} 但缺乏具體證據的介面方法
 * <p>
 * 每條規則皆為 fail-closed：僅 {@link TypeKind#INTERFACE} 宣告型別參與評估，任何無法明確
 * 判定的情況一律回傳空值，交由呼叫端視為非資料存取進入點
 * <p>
 * 純元件，不做任何 I/O 或記錄
 */
public final class DataAccessEvidence {

    private static final double MYBATIS_CONFIDENCE = 1.0d;
    private static final double SPRING_DATA_CONFIDENCE = 1.0d;
    private static final double WITHOUT_EVIDENCE_CONFIDENCE = 0.5d;

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
            ClassMetadata declaringType, MethodSignature declaredMethod, MethodTarget declarationTarget) {
        Objects.requireNonNull(declaringType, "declaringType is required");
        Objects.requireNonNull(declaredMethod, "declaredMethod is required");
        Objects.requireNonNull(declarationTarget, "declarationTarget is required");

        if (declaringType.kind() != TypeKind.INTERFACE) {
            return Optional.empty();
        }

        return matchMybatisMapper(declaringType, declaredMethod, declarationTarget)
                .or(() -> matchSpringDataRepository(declaringType, declaredMethod, declarationTarget))
                .or(() -> matchDataAccessWithoutEvidence(declaringType, declaredMethod, declarationTarget));
    }

    private Optional<EvidenceMatch> matchMybatisMapper(
            ClassMetadata declaringType, MethodSignature declaredMethod, MethodTarget declarationTarget) {
        if (Objects.isNull(declaredMethod.sqlSource())) {
            return Optional.empty();
        }
        return Optional.of(matched(
                ResolutionStrategy.MYBATIS_MAPPER, declaringType, declaredMethod, declarationTarget,
                MYBATIS_CONFIDENCE, List.of("mapper SQL: " + declaredMethod.sql())));
    }

    /**
     * 介面的 {@code extends} 子句被 {@code ClassMetadataExtractor} 歸入 implementedTypes，
     * 一般型別的父類別則落在 extendedTypes；此規則已由 {@link #evaluate} 限定僅介面參與，
     * 因此取兩者聯集比對已知父介面不會誤判類別
     */
    private Optional<EvidenceMatch> matchSpringDataRepository(
            ClassMetadata declaringType, MethodSignature declaredMethod, MethodTarget declarationTarget) {
        return Stream.concat(declaringType.extendedTypes().stream(), declaringType.implementedTypes().stream())
                .filter(SPRING_DATA_SUPERTYPES::contains)
                .findFirst()
                .map(supertype -> matched(
                        ResolutionStrategy.SPRING_DATA_REPOSITORY, declaringType, declaredMethod, declarationTarget,
                        SPRING_DATA_CONFIDENCE, List.of("extends " + supertype)));
    }

    private Optional<EvidenceMatch> matchDataAccessWithoutEvidence(
            ClassMetadata declaringType, MethodSignature declaredMethod, MethodTarget declarationTarget) {
        return declaringType.annotations().stream()
                .map(AnnotationSimpleNames::simpleName)
                .filter(DATA_ACCESS_ANNOTATIONS::contains)
                .findFirst()
                .map(annotation -> matched(
                        ResolutionStrategy.DATA_ACCESS_WITHOUT_EVIDENCE, declaringType, declaredMethod,
                        declarationTarget, WITHOUT_EVIDENCE_CONFIDENCE,
                        List.of("@" + annotation + " without SQL or known supertype")));
    }

    private static EvidenceMatch matched(
            ResolutionStrategy strategy, ClassMetadata declaringType, MethodSignature declaredMethod,
            MethodTarget declarationTarget, double confidence, List<String> evidence) {
        return new EvidenceMatch(
                strategy, opaqueSymbolOf(declaringType, declaredMethod), Optional.of(declarationTarget),
                confidence, evidence);
    }

    private static String opaqueSymbolOf(ClassMetadata declaringType, MethodSignature declaredMethod) {
        return declaringType.fullyQualifiedName() + "#" + declaredMethod.name()
                + "(" + String.join(",", declaredMethod.paramTypes()) + ")";
    }
}
