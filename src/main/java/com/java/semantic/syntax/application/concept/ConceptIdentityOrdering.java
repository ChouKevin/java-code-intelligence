package com.java.semantic.syntax.application.concept;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.FieldConceptIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.MethodConceptIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.TypeConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.ApiRouteConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.MqDestinationConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.ScheduleConceptIdentity;
import com.java.semantic.syntax.application.concept.MapperConceptIdentity.MapperStatementConceptIdentity;
import com.java.semantic.syntax.application.concept.MapperConceptIdentity.MapperStatementVariantEvidenceIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.AnnotationIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.AnnotationUsageConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.DeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.FieldDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.ResolvedAnnotationIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.ResolvedMethodDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.UnresolvedAnnotationIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.UnresolvedMethodDeclarationSubjectIdentity;
import com.java.semantic.syntax.domain.MapperStatementIdentity;
import com.java.semantic.syntax.domain.MapperStatementKey;

/** 概念 identity 與 catalog 項目的唯一型別化排序擁有者 */
public final class ConceptIdentityOrdering {

    private static final Comparator<ConceptIdentity> IDENTITY_COMPARATOR = ConceptIdentityOrdering::compareIdentity;

    private static final Comparator<ConceptCatalogEntry> CATALOG_ENTRY_COMPARATOR =
            Comparator.comparing(ConceptCatalogEntry::identity, IDENTITY_COMPARATOR);

    private ConceptIdentityOrdering() {
    }

    /** 回傳所有 concrete ConceptIdentity 的確定性完整排序 */
    public static Comparator<ConceptIdentity> comparator() {
        return IDENTITY_COMPARATOR;
    }

    /** 回傳以 typed identity 排序的 catalog 項目比較器 */
    public static Comparator<ConceptCatalogEntry> catalogEntryComparator() {
        return CATALOG_ENTRY_COMPARATOR;
    }

    private static int compareIdentity(ConceptIdentity left, ConceptIdentity right) {
        int kindComparison = left.identityKind().compareTo(right.identityKind());
        if (kindComparison != 0) {
            return kindComparison;
        }
        return switch (left) {
            case TypeConceptIdentity leftIdentity -> compareTypeConcept(
                    leftIdentity,
                    (TypeConceptIdentity) right);
            case MethodConceptIdentity leftIdentity -> compareMethodTarget(
                    leftIdentity.target(),
                    ((MethodConceptIdentity) right).target());
            case FieldConceptIdentity leftIdentity -> compareFieldConcept(
                    leftIdentity,
                    (FieldConceptIdentity) right);
            case AnnotationUsageConceptIdentity leftIdentity -> compareAnnotationUsage(
                    leftIdentity,
                    (AnnotationUsageConceptIdentity) right);
            case TypeUsageConceptIdentity leftIdentity -> compareTypeUsage(
                    leftIdentity,
                    (TypeUsageConceptIdentity) right);
            case ApiRouteConceptIdentity leftIdentity -> compareApiRoute(
                    leftIdentity,
                    (ApiRouteConceptIdentity) right);
            case MqDestinationConceptIdentity leftIdentity -> compareMqDestination(
                    leftIdentity,
                    (MqDestinationConceptIdentity) right);
            case ScheduleConceptIdentity leftIdentity -> compareSchedule(
                    leftIdentity,
                    (ScheduleConceptIdentity) right);
            case MapperStatementConceptIdentity leftIdentity -> compareMapperStatement(
                    leftIdentity,
                    (MapperStatementConceptIdentity) right);
            case MapperStatementVariantEvidenceIdentity leftIdentity -> compareMapperVariant(
                    leftIdentity.mapperStatement(),
                    ((MapperStatementVariantEvidenceIdentity) right).mapperStatement());
        };
    }

    private static int compareTypeConcept(TypeConceptIdentity left, TypeConceptIdentity right) {
        return compareSourceType(left.type(), right.type());
    }

    private static int compareFieldConcept(FieldConceptIdentity left, FieldConceptIdentity right) {
        return firstNonZero(
                compareSourceType(left.field().ownerType(), right.field().ownerType()),
                left.field().name().compareTo(right.field().name()));
    }

    private static int compareAnnotationUsage(
            AnnotationUsageConceptIdentity left,
            AnnotationUsageConceptIdentity right) {
        int ownerComparison = compareDeclarationSubject(left.annotatedDeclaration(), right.annotatedDeclaration());
        if (ownerComparison != 0) {
            return ownerComparison;
        }
        return compareAnnotationIdentity(left.annotationIdentity(), right.annotationIdentity());
    }

    private static int compareTypeUsage(TypeUsageConceptIdentity left, TypeUsageConceptIdentity right) {
        int ownerComparison = compareDeclarationSubject(left.owner(), right.owner());
        if (ownerComparison != 0) {
            return ownerComparison;
        }
        int locationComparison = firstNonZero(
                left.location().slot().compareTo(right.location().slot()),
                Integer.compare(left.location().index(), right.location().index()));
        if (locationComparison != 0) {
            return locationComparison;
        }
        int pathComparison = compareTypeUsagePaths(left.path(), right.path());
        if (pathComparison != 0) {
            return pathComparison;
        }
        int typeComparison = compareJavaType(left.referencedType().javaType(), right.referencedType().javaType());
        if (typeComparison != 0) {
            return typeComparison;
        }
        return Integer.compare(left.referencedType().arrayDimensions(), right.referencedType().arrayDimensions());
    }

    private static int compareApiRoute(ApiRouteConceptIdentity left, ApiRouteConceptIdentity right) {
        int targetComparison = compareMethodTarget(left.target(), right.target());
        if (targetComparison != 0) {
            return targetComparison;
        }
        return firstNonZero(
                left.httpVerb().compareTo(right.httpVerb()),
                left.route().compareTo(right.route()));
    }

    private static int compareMqDestination(MqDestinationConceptIdentity left, MqDestinationConceptIdentity right) {
        int targetComparison = compareMethodTarget(left.target(), right.target());
        if (targetComparison != 0) {
            return targetComparison;
        }
        return firstNonZero(
                left.broker().compareTo(right.broker()),
                left.destination().compareTo(right.destination()));
    }

    private static int compareSchedule(ScheduleConceptIdentity left, ScheduleConceptIdentity right) {
        int targetComparison = compareMethodTarget(left.target(), right.target());
        if (targetComparison != 0) {
            return targetComparison;
        }
        int triggerComparison = left.triggerKind().compareTo(right.triggerKind());
        if (triggerComparison != 0) {
            return triggerComparison;
        }
        return compareOptionalString(left.triggerValue(), right.triggerValue());
    }

    private static int compareMapperStatement(
            MapperStatementConceptIdentity left,
            MapperStatementConceptIdentity right) {
        return compareMapperStatementKey(left.statementKey(), right.statementKey());
    }

    private static int compareMapperVariant(MapperStatementIdentity left, MapperStatementIdentity right) {
        int componentComparison = firstNonZero(
                compareMapperStatementKey(left.statementKey(), right.statementKey()),
                left.resourcePath().compareTo(right.resourcePath()));
        if (componentComparison != 0) {
            return componentComparison;
        }
        int databaseIdComparison = compareOptionalString(left.databaseId(), right.databaseId());
        if (databaseIdComparison != 0) {
            return databaseIdComparison;
        }
        return firstNonZero(
                Integer.compare(left.documentOrdinal(), right.documentOrdinal()),
                left.representation().compareTo(right.representation()));
    }

    private static int compareMapperStatementKey(MapperStatementKey left, MapperStatementKey right) {
        return firstNonZero(
                left.namespace().compareTo(right.namespace()),
                left.statementId().compareTo(right.statementId()));
    }

    private static int compareDeclarationSubject(DeclarationSubjectIdentity left, DeclarationSubjectIdentity right) {
        int kindComparison = Integer.compare(declarationSubjectKind(left), declarationSubjectKind(right));
        if (kindComparison != 0) {
            return kindComparison;
        }
        return switch (left) {
            case TypeDeclarationSubjectIdentity leftIdentity -> firstNonZero(
                    compareSourceType(leftIdentity.type(), ((TypeDeclarationSubjectIdentity) right).type()));
            case ResolvedMethodDeclarationSubjectIdentity leftIdentity -> compareMethodTarget(
                    leftIdentity.target(),
                    ((ResolvedMethodDeclarationSubjectIdentity) right).target());
            case UnresolvedMethodDeclarationSubjectIdentity leftIdentity -> compareUnresolvedMethodSubject(
                    leftIdentity,
                    (UnresolvedMethodDeclarationSubjectIdentity) right);
            case FieldDeclarationSubjectIdentity leftIdentity -> firstNonZero(
                    compareSourceType(
                            leftIdentity.field().ownerType(),
                            ((FieldDeclarationSubjectIdentity) right).field().ownerType()),
                    leftIdentity.field().name().compareTo(((FieldDeclarationSubjectIdentity) right).field().name()));
        };
    }

    private static int declarationSubjectKind(DeclarationSubjectIdentity identity) {
        return switch (identity) {
            case TypeDeclarationSubjectIdentity ignored -> 0;
            case ResolvedMethodDeclarationSubjectIdentity ignored -> 1;
            case UnresolvedMethodDeclarationSubjectIdentity ignored -> 2;
            case FieldDeclarationSubjectIdentity ignored -> 3;
        };
    }

    private static int compareUnresolvedMethodSubject(
            UnresolvedMethodDeclarationSubjectIdentity left,
            UnresolvedMethodDeclarationSubjectIdentity right) {
        int componentComparison = firstNonZero(
                compareSourceType(left.owner(), right.owner()),
                left.signature().methodName().compareTo(right.signature().methodName()));
        if (componentComparison != 0) {
            return componentComparison;
        }
        return compareStringLists(left.signature().parameterTypes(), right.signature().parameterTypes());
    }

    private static int compareAnnotationIdentity(AnnotationIdentity left, AnnotationIdentity right) {
        int kindComparison = Integer.compare(annotationIdentityKind(left), annotationIdentityKind(right));
        if (kindComparison != 0) {
            return kindComparison;
        }
        return switch (left) {
            case ResolvedAnnotationIdentity leftIdentity -> compareJavaType(
                    leftIdentity.javaType(),
                    ((ResolvedAnnotationIdentity) right).javaType());
            case UnresolvedAnnotationIdentity leftIdentity -> leftIdentity.writtenName().compareTo(
                    ((UnresolvedAnnotationIdentity) right).writtenName());
        };
    }

    private static int annotationIdentityKind(AnnotationIdentity identity) {
        return switch (identity) {
            case ResolvedAnnotationIdentity ignored -> 0;
            case UnresolvedAnnotationIdentity ignored -> 1;
        };
    }

    private static int compareTypeUsagePaths(List<TypeUsagePath> left, List<TypeUsagePath> right) {
        int commonLength = Math.min(left.size(), right.size());
        for (int index = 0; index < commonLength; index++) {
            int pathComparison = compareTypeUsagePath(left.get(index), right.get(index));
            if (pathComparison != 0) {
                return pathComparison;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private static int compareTypeUsagePath(TypeUsagePath left, TypeUsagePath right) {
        int kindComparison = Integer.compare(typeUsagePathKind(left), typeUsagePathKind(right));
        if (kindComparison != 0) {
            return kindComparison;
        }
        return switch (left) {
            case TypeUsagePath.TypeArgument leftPath -> Integer.compare(
                    leftPath.index(),
                    ((TypeUsagePath.TypeArgument) right).index());
            case TypeUsagePath.WildcardExtendsBound ignored -> 0;
            case TypeUsagePath.WildcardSuperBound ignored -> 0;
            case TypeUsagePath.TypeVariableBound leftPath -> Integer.compare(
                    leftPath.index(),
                    ((TypeUsagePath.TypeVariableBound) right).index());
        };
    }

    private static int typeUsagePathKind(TypeUsagePath path) {
        return switch (path) {
            case TypeUsagePath.TypeArgument ignored -> 0;
            case TypeUsagePath.WildcardExtendsBound ignored -> 1;
            case TypeUsagePath.WildcardSuperBound ignored -> 2;
            case TypeUsagePath.TypeVariableBound ignored -> 3;
        };
    }

    private static int compareMethodTarget(MethodTarget left, MethodTarget right) {
        int componentComparison = firstNonZero(
                left.sourceFile().compareTo(right.sourceFile()),
                left.packageName().compareTo(right.packageName()),
                left.className().compareTo(right.className()),
                left.methodName().compareTo(right.methodName()));
        if (componentComparison != 0) {
            return componentComparison;
        }
        return compareStringLists(left.parameterTypes(), right.parameterTypes());
    }

    private static int compareJavaType(JavaTypeIdentity left, JavaTypeIdentity right) {
        return firstNonZero(
                left.packageName().compareTo(right.packageName()),
                left.className().compareTo(right.className()));
    }

    private static int compareSourceType(SourceTypeIdentity left, SourceTypeIdentity right) {
        int javaTypeComparison = compareJavaType(left.javaType(), right.javaType());
        return javaTypeComparison != 0 ? javaTypeComparison : left.sourceFile().compareTo(right.sourceFile());
    }

    private static int compareOptionalString(Optional<String> left, Optional<String> right) {
        int presenceComparison = Boolean.compare(left.isPresent(), right.isPresent());
        if (presenceComparison != 0) {
            return presenceComparison;
        }
        return left.isPresent() ? left.orElseThrow().compareTo(right.orElseThrow()) : 0;
    }

    private static int compareStringLists(List<String> left, List<String> right) {
        int commonLength = Math.min(left.size(), right.size());
        for (int index = 0; index < commonLength; index++) {
            int componentComparison = left.get(index).compareTo(right.get(index));
            if (componentComparison != 0) {
                return componentComparison;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private static int firstNonZero(int... comparisons) {
        for (int componentComparison : comparisons) {
            if (componentComparison != 0) {
                return componentComparison;
            }
        }
        return 0;
    }
}
