package com.java.semantic.syntax.application.concept;

import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.FieldConceptIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.MethodConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.ApiRouteConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.MqDestinationConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.ScheduleConceptIdentity;
import com.java.semantic.syntax.application.concept.MapperConceptIdentity.MapperStatementConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.AnnotationUsageConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.ResolvedMethodDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageConceptIdentity;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** 結構化概念目錄項目，搜尋文件只在讀取時從 typed identity 與 details 衍生 */
public record ConceptCatalogEntry(
        String providerId,
        ConceptIdentity identity,
        String displayValue,
        String packageName,
        Optional<String> declaringType,
        ConceptAuthority authority,
        Set<ConceptIdentity> evidence,
        Optional<String> methodReturnType,
        Optional<String> annotationWrittenName,
        Optional<MapperStatementMethodMapping> mapperStatementMapping,
        Optional<FieldConceptDetails> fieldDetails) {

    public ConceptCatalogEntry {
        providerId = requiredText(providerId, "providerId");
        identity = Objects.requireNonNull(identity, "identity is required");
        displayValue = requiredText(displayValue, "displayValue");
        packageName = Objects.requireNonNull(packageName, "packageName is required");
        declaringType = Objects.requireNonNull(declaringType, "declaringType is required");
        authority = Objects.requireNonNull(authority, "authority is required");
        methodReturnType = Objects.requireNonNull(methodReturnType, "methodReturnType is required");
        annotationWrittenName = Objects.requireNonNull(annotationWrittenName, "annotationWrittenName is required");
        mapperStatementMapping = Objects.requireNonNull(
                mapperStatementMapping, "mapperStatementMapping is required");
        fieldDetails = Objects.requireNonNull(fieldDetails, "fieldDetails is required");
        TreeSet<ConceptIdentity> orderedEvidence = new TreeSet<>(ConceptIdentityOrdering.comparator());
        orderedEvidence.addAll(Objects.requireNonNull(evidence, "evidence is required"));
        evidence = Collections.unmodifiableSet(new LinkedHashSet<>(orderedEvidence));
        Assert.isTrue(evidence.contains(identity), "evidence must contain the typed identity");
        Assert.isTrue(
                (identity instanceof MapperStatementConceptIdentity)
                        == mapperStatementMapping.isPresent(),
                "mapper statement mapping must match identity kind");
        Assert.isTrue(
                (identity instanceof MethodConceptIdentity) || methodReturnType.isEmpty(),
                "only method concepts may declare a return type");
        Assert.isTrue(
                (identity instanceof AnnotationUsageConceptIdentity) || annotationWrittenName.isEmpty(),
                "only annotation usage concepts may declare a written annotation name");
        Assert.isTrue(
                (identity instanceof FieldConceptIdentity) || fieldDetails.isEmpty(),
                "only field concepts may declare field details");
    }

    public ConceptCatalogEntry(
            String providerId,
            ConceptIdentity identity,
            String displayValue,
            String packageName,
            Optional<String> declaringType,
            ConceptAuthority authority,
            Set<ConceptIdentity> evidence) {
        this(
                providerId,
                identity,
                displayValue,
                packageName,
                declaringType,
                authority,
                evidence,
                Optional.empty(),
                Optional.empty(),
                defaultMapperStatementMapping(identity),
                Optional.empty());
    }

    /** 建立帶有方法回傳型別 detail 的非 mapper 概念 */
    public ConceptCatalogEntry(
            String providerId,
            ConceptIdentity identity,
            String displayValue,
            String packageName,
            Optional<String> declaringType,
            ConceptAuthority authority,
            Set<ConceptIdentity> evidence,
            Optional<String> methodReturnType) {
        this(
                providerId,
                identity,
                displayValue,
                packageName,
                declaringType,
                authority,
                evidence,
                methodReturnType,
                Optional.empty(),
                defaultMapperStatementMapping(identity),
                Optional.empty());
    }

    /** 建立帶有 annotation 搜尋 detail 的非 mapper 概念 */
    public ConceptCatalogEntry(
            String providerId,
            ConceptIdentity identity,
            String displayValue,
            String packageName,
            Optional<String> declaringType,
            ConceptAuthority authority,
            Set<ConceptIdentity> evidence,
            Optional<String> methodReturnType,
            Optional<String> annotationWrittenName) {
        this(
                providerId,
                identity,
                displayValue,
                packageName,
                declaringType,
                authority,
                evidence,
                methodReturnType,
                annotationWrittenName,
                defaultMapperStatementMapping(identity),
                Optional.empty());
    }

    /** 概念種類完全由唯一 typed identity 導出 */
    public ConceptKind kind() {
        return identity.kind();
    }

    /** 僅在 identity 直接或其方法 subject 保存 MethodTarget 時投影 graph target */
    public Optional<MethodTarget> target() {
        if (identity instanceof MethodConceptIdentity methodIdentity) {
            return Optional.of(methodIdentity.target());
        }
        if (identity instanceof ApiRouteConceptIdentity apiRouteIdentity) {
            return Optional.of(apiRouteIdentity.target());
        }
        if (identity instanceof MqDestinationConceptIdentity mqDestinationIdentity) {
            return Optional.of(mqDestinationIdentity.target());
        }
        if (identity instanceof ScheduleConceptIdentity scheduleIdentity) {
            return Optional.of(scheduleIdentity.target());
        }
        if (identity instanceof AnnotationUsageConceptIdentity annotationIdentity
                && annotationIdentity.annotatedDeclaration() instanceof ResolvedMethodDeclarationSubjectIdentity subject) {
            return Optional.of(subject.target());
        }
        if (identity instanceof TypeUsageConceptIdentity typeUsageIdentity
                && typeUsageIdentity.owner() instanceof ResolvedMethodDeclarationSubjectIdentity subject) {
            return Optional.of(subject.target());
        }
        return Optional.empty();
    }

    /** 同一 typed identity 的 evidence 與 details 以確定順序合併 */
    public ConceptCatalogEntry merge(ConceptCatalogEntry other) {
        ConceptCatalogEntry entry = Objects.requireNonNull(other, "other is required");
        Assert.isTrue(identity.equals(entry.identity), "only equal typed identities may merge");
        TreeSet<ConceptIdentity> mergedEvidence = new TreeSet<>(ConceptIdentityOrdering.comparator());
        mergedEvidence.addAll(evidence);
        mergedEvidence.addAll(entry.evidence);
        return new ConceptCatalogEntry(
                firstInOrder(providerId, entry.providerId),
                identity,
                firstInOrder(displayValue, entry.displayValue),
                firstInOrder(packageName, entry.packageName),
                firstInOrder(declaringType, entry.declaringType),
                mostAuthoritative(authority, entry.authority),
                mergedEvidence,
                firstInOrder(methodReturnType, entry.methodReturnType),
                firstInOrder(annotationWrittenName, entry.annotationWrittenName),
                mergeMapperStatementMapping(mapperStatementMapping, entry.mapperStatementMapping),
                mergeFieldDetails(fieldDetails, entry.fieldDetails));
    }

    private static Optional<MapperStatementMethodMapping> defaultMapperStatementMapping(
            ConceptIdentity identity) {
        if (identity instanceof MapperStatementConceptIdentity mapperIdentity) {
            return Optional.of(MapperStatementMethodMapping.fromDeclarations(
                    mapperIdentity,
                    0,
                    true,
                    Collections.emptyList()));
        }
        return Optional.empty();
    }

    private static Optional<MapperStatementMethodMapping> mergeMapperStatementMapping(
            Optional<MapperStatementMethodMapping> left,
            Optional<MapperStatementMethodMapping> right) {
        if (left.isPresent() && right.isPresent()) {
            return Optional.of(left.orElseThrow().merge(right.orElseThrow()));
        }
        return left.isPresent() ? left : right;
    }

    private static Optional<FieldConceptDetails> mergeFieldDetails(
            Optional<FieldConceptDetails> left,
            Optional<FieldConceptDetails> right) {
        return left.isPresent() ? left : right;
    }

    private static String firstInOrder(String left, String right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static Optional<String> firstInOrder(Optional<String> left, Optional<String> right) {
        if (left.isPresent() && right.isPresent()) {
            return Optional.of(firstInOrder(left.orElseThrow(), right.orElseThrow()));
        }
        return left.isPresent() ? left : right;
    }

    private static ConceptAuthority mostAuthoritative(ConceptAuthority left, ConceptAuthority right) {
        return left.ordinal() <= right.ordinal() ? left : right;
    }

    private static String requiredText(String value, String fieldName) {
        String text = Objects.requireNonNull(value, fieldName + " is required");
        Assert.isTrue(StringUtils.hasText(text), fieldName + " is required");
        return text;
    }
}
