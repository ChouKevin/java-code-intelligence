package com.java.semantic.syntax.application;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.syntax.application.ConceptIdentity.AnnotationUsageConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.ApiRouteConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.MethodConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.MqDestinationConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.MapperStatementVariantEvidenceIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.ResolvedMethodDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.ScheduleConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.TypeUsageConceptIdentity;
import com.java.semantic.syntax.domain.MapperStatementIdentity;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 一筆結構化概念目錄項目
 * canonicalValue 只用於顯示與 CANONICAL_EXACT 查詢，不參與 identity 合併
 * searchTokens 由結構化 metadata 導出，不包含來源本文或單獨 metadata sentinel
 */
public record ConceptCatalogEntry(
        String providerId,
        ConceptIdentity identity,
        String canonicalValue,
        String displayValue,
        Set<String> searchTokens,
        String packageName,
        Optional<String> declaringType,
        ConceptAuthority authority,
        Set<ConceptIdentity> evidence,
        Optional<MapperStatementMethodMapping> mapperStatementMapping) {

    private static final Comparator<MapperStatementIdentity> MAPPER_VARIANT_IDENTITY_COMPARATOR =
            Comparator.comparing(MapperStatementIdentity::namespace)
                    .thenComparing(MapperStatementIdentity::statementId)
                    .thenComparing(MapperStatementIdentity::resourcePath)
                    .thenComparing(identity -> identity.databaseId().isPresent())
                    .thenComparing(identity -> identity.databaseId().orElse(""))
                    .thenComparingInt(MapperStatementIdentity::documentOrdinal)
                    .thenComparing(MapperStatementIdentity::representation);

    public ConceptCatalogEntry {
        providerId = ConceptIdentitySupport.requiredText(providerId, "providerId");
        identity = Objects.requireNonNull(identity, "identity is required");
        canonicalValue = ConceptIdentitySupport.requiredText(canonicalValue, "canonicalValue");
        displayValue = ConceptIdentitySupport.requiredText(displayValue, "displayValue");
        packageName = Objects.requireNonNull(packageName, "packageName is required");
        declaringType = Objects.requireNonNull(declaringType, "declaringType is required");
        authority = Objects.requireNonNull(authority, "authority is required");
        mapperStatementMapping = Objects.requireNonNull(
                mapperStatementMapping, "mapperStatementMapping is required");
        TreeSet<String> tokens = new TreeSet<>();
        for (String searchToken : Objects.requireNonNull(searchTokens, "searchTokens are required")) {
            String token = ConceptIdentitySupport.requiredText(searchToken, "searchToken");
            tokens.add(token);
        }
        searchTokens = Collections.unmodifiableSet(new LinkedHashSet<>(tokens));
        TreeSet<ConceptIdentity> orderedEvidence = new TreeSet<>(identityComparator());
        orderedEvidence.addAll(Objects.requireNonNull(evidence, "evidence is required"));
        evidence = Collections.unmodifiableSet(new LinkedHashSet<>(orderedEvidence));
        Assert.isTrue(evidence.contains(identity), "evidence must contain the typed identity");
        Assert.isTrue(
                (identity instanceof ConceptIdentity.MapperStatementConceptIdentity)
                        == mapperStatementMapping.isPresent(),
                "mapper statement mapping must match identity kind");
    }

    public ConceptCatalogEntry(
            String providerId,
            ConceptIdentity identity,
            String canonicalValue,
            String displayValue,
            Set<String> searchTokens,
            String packageName,
            Optional<String> declaringType,
            ConceptAuthority authority,
            Set<ConceptIdentity> evidence) {
        this(
                providerId,
                identity,
                canonicalValue,
                displayValue,
                searchTokens,
                packageName,
                declaringType,
                authority,
                evidence,
                defaultMapperStatementMapping(identity));
    }

    /** 概念種類完全由唯一 typed identity 導出 */
    public ConceptKind kind() {
        return identity.kind();
    }

    /** 僅在 identity 直接或其方法 subject 保存 canonical MethodTarget 時投影 graph target */
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
                && typeUsageIdentity.ownerDeclaration() instanceof ResolvedMethodDeclarationSubjectIdentity subject) {
            return Optional.of(subject.target());
        }
        return Optional.empty();
    }

    /** 同一 typed identity 的 evidence 以型別化集合合併，絕不以 canonicalValue 當 key */
    public ConceptCatalogEntry merge(ConceptCatalogEntry other) {
        ConceptCatalogEntry entry = Objects.requireNonNull(other, "other is required");
        Assert.isTrue(identity.equals(entry.identity), "only equal typed identities may merge");
        TreeSet<String> mergedTokens = new TreeSet<>(searchTokens);
        mergedTokens.addAll(entry.searchTokens);
        TreeSet<ConceptIdentity> mergedEvidence = new TreeSet<>(identityComparator());
        mergedEvidence.addAll(evidence);
        mergedEvidence.addAll(entry.evidence);
        return new ConceptCatalogEntry(
                firstInCanonicalOrder(providerId, entry.providerId),
                identity,
                firstInCanonicalOrder(canonicalValue, entry.canonicalValue),
                firstInCanonicalOrder(displayValue, entry.displayValue),
                mergedTokens,
                firstInCanonicalOrder(packageName, entry.packageName),
                firstInCanonicalOrder(declaringType, entry.declaringType),
                mostAuthoritative(authority, entry.authority),
                mergedEvidence,
                mergeMapperStatementMapping(mapperStatementMapping, entry.mapperStatementMapping));
    }

    private static Optional<MapperStatementMethodMapping> defaultMapperStatementMapping(
            ConceptIdentity identity) {
        if (identity instanceof ConceptIdentity.MapperStatementConceptIdentity mapperIdentity) {
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

    private static String firstInCanonicalOrder(String left, String right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static Optional<String> firstInCanonicalOrder(Optional<String> left, Optional<String> right) {
        if (left.isPresent() && right.isPresent()) {
            return Optional.of(firstInCanonicalOrder(left.orElseThrow(), right.orElseThrow()));
        }
        return left.isPresent() ? left : right;
    }

    private static ConceptAuthority mostAuthoritative(ConceptAuthority left, ConceptAuthority right) {
        return left.ordinal() <= right.ordinal() ? left : right;
    }

    private static Comparator<ConceptIdentity> identityComparator() {
        return Comparator.comparing(ConceptIdentity::kind)
                .thenComparing(ConceptIdentity::canonicalOrderKey)
                .thenComparing(ConceptCatalogEntry::compareCanonicalCollision);
    }

    private static int compareCanonicalCollision(ConceptIdentity left, ConceptIdentity right) {
        int identityTypeOrder = left.getClass().getName().compareTo(right.getClass().getName());
        if (identityTypeOrder != 0) {
            return identityTypeOrder;
        }
        if (left instanceof MapperStatementVariantEvidenceIdentity leftVariant
                && right instanceof MapperStatementVariantEvidenceIdentity rightVariant) {
            return MAPPER_VARIANT_IDENTITY_COMPARATOR.compare(
                    leftVariant.mapperStatement(),
                    rightVariant.mapperStatement());
        }
        return 0;
    }
}
