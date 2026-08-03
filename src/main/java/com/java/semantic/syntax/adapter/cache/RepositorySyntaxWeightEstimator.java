package com.java.semantic.syntax.adapter.cache;

import com.java.semantic.syntax.domain.ArrayTypeReference;
import com.java.semantic.syntax.domain.CompositeTypeReference;
import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.InferredTypeReference;
import com.java.semantic.syntax.domain.MapperEvidenceIndex;
import com.java.semantic.syntax.domain.NamedTypeReference;
import com.java.semantic.syntax.domain.ParameterizedTypeReference;
import com.java.semantic.syntax.domain.PrimitiveTypeReference;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceFieldMetadata;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.TypeReference;
import com.java.semantic.syntax.domain.TypeVariableReference;
import com.java.semantic.syntax.domain.WildcardTypeReference;

import java.util.List;
import java.util.Objects;

/** 依 RepositorySyntax 的結構性 metadata 估算快取權重且不讀取原始碼內容 */
final class RepositorySyntaxWeightEstimator {

    int estimate(RepositorySyntax syntax) {
        RepositorySyntax requiredSyntax = Objects.requireNonNull(syntax, "syntax is required");
        int weight = 1;
        weight = collectionWeight(weight, requiredSyntax.entryPoints().size());
        for (EntryPointClass entryPoint : requiredSyntax.entryPoints()) {
            weight = saturatedAdd(weight, 1);
            weight = collectionWeight(weight, entryPoint.basePaths().size());
            weight = collectionWeight(weight, entryPoint.methods().size());
        }
        weight = collectionWeight(weight, requiredSyntax.sourceTypes().size());
        for (SourceTypeMetadata sourceType : requiredSyntax.sourceTypes()) {
            weight = saturatedAdd(weight, sourceTypeWeight(sourceType));
        }
        weight = collectionWeight(weight, requiredSyntax.extractionOutcomes().size());
        weight = saturatedAdd(weight, requiredSyntax.mapperEvidenceIndex()
                .map(this::mapperEvidenceWeight)
                .orElse(0));
        return weight;
    }

    private int mapperEvidenceWeight(MapperEvidenceIndex mapperEvidenceIndex) {
        int weight = 1;
        weight = collectionWeight(weight, mapperEvidenceIndex.statements().size());
        for (int index = 0; index < mapperEvidenceIndex.statements().size(); index++) {
            weight = saturatedAdd(weight, 2);
            weight = collectionWeight(weight, mapperEvidenceIndex.statements().get(index).includeRefIds().size());
            weight = saturatedAdd(weight, mapperEvidenceIndex.statements().get(index).mappedMethodTarget().isPresent() ? 1 : 0);
        }
        weight = collectionWeight(weight, mapperEvidenceIndex.fragments().size());
        for (int index = 0; index < mapperEvidenceIndex.fragments().size(); index++) {
            weight = saturatedAdd(weight, 2);
        }
        return weight;
    }

    private int sourceTypeWeight(SourceTypeMetadata sourceType) {
        int weight = 5;
        weight = collectionWeight(weight, sourceType.relationships().extendedTypes().size());
        weight = collectionWeight(weight, sourceType.relationships().implementedTypes().size());
        weight = collectionWeight(weight, sourceType.members().fields().size());
        for (SourceFieldMetadata field : sourceType.members().fields()) {
            weight = saturatedAdd(weight, 2);
            weight = collectionWeight(weight, field.annotationEvidence().size());
            weight = typeReferenceWeight(weight, field.typeReference());
        }
        weight = collectionWeight(weight, sourceType.members().methods().size());
        for (SourceMethodMetadata method : sourceType.members().methods()) {
            weight = methodWeight(weight, method);
        }
        weight = collectionWeight(weight, sourceType.frameworkFacts().annotations().size());
        weight = collectionWeight(weight, sourceType.frameworkFacts().profiles().size());
        weight = collectionWeight(weight, sourceType.frameworkFacts().beanQualifiers().size());
        return collectionWeight(weight, sourceType.compilationUnit().imports().size());
    }

    private int methodWeight(int weight, SourceMethodMetadata method) {
        int result = saturatedAdd(weight, 2);
        result = collectionWeight(result, method.paramTypes().size());
        result = saturatedAdd(result, method.annotationSqlLocation().isPresent() ? 1 : 0);
        result = typeReferenceCollectionWeight(result, method.parameterTypeReferences());
        if (method.returnType().isPresent()) {
            result = typeReferenceWeight(result, method.returnType().orElseThrow());
        }
        result = collectionWeight(result, method.invocations().size());
        result = collectionWeight(result, method.annotationEvidence().size());
        result = collectionWeight(result, method.bodyTypeReferences().size());
        return saturatedAdd(result, method.analysisTarget().target().isPresent() ? 1 : 0);
    }

    private int typeReferenceCollectionWeight(int current, List<TypeReference> typeReferences) {
        List<TypeReference> requiredTypeReferences = Objects.requireNonNull(
                typeReferences, "typeReferences are required");
        int weight = collectionWeight(current, requiredTypeReferences.size());
        for (TypeReference typeReference : requiredTypeReferences) {
            weight = typeReferenceWeight(weight, typeReference);
        }
        return weight;
    }

    private int typeReferenceWeight(int current, TypeReference typeReference) {
        TypeReference requiredTypeReference = Objects.requireNonNull(typeReference, "typeReference is required");
        int weight = saturatedAdd(current, 1);
        return switch (requiredTypeReference) {
            case ParameterizedTypeReference parameterizedType -> {
                int rawTypeWeight = typeReferenceWeight(weight, parameterizedType.rawType());
                yield typeReferenceCollectionWeight(rawTypeWeight, parameterizedType.typeArguments());
            }
            case ArrayTypeReference arrayType -> typeReferenceWeight(weight, arrayType.elementType());
            case WildcardTypeReference wildcardType -> {
                int boundedWeight = weight;
                if (wildcardType.upperBound().isPresent()) {
                    boundedWeight = typeReferenceWeight(boundedWeight, wildcardType.upperBound().orElseThrow());
                }
                if (wildcardType.lowerBound().isPresent()) {
                    boundedWeight = typeReferenceWeight(boundedWeight, wildcardType.lowerBound().orElseThrow());
                }
                yield boundedWeight;
            }
            case TypeVariableReference typeVariable ->
                    typeReferenceCollectionWeight(weight, typeVariable.upperBounds());
            case CompositeTypeReference compositeType ->
                    typeReferenceCollectionWeight(weight, compositeType.alternatives());
            case NamedTypeReference ignored -> weight;
            case PrimitiveTypeReference ignored -> weight;
            case InferredTypeReference ignored -> weight;
        };
    }

    private int collectionWeight(int current, int elements) {
        return saturatedAdd(saturatedAdd(current, 1), elements);
    }

    private int saturatedAdd(int left, int right) {
        if (left > Integer.MAX_VALUE - right) {
            return Integer.MAX_VALUE;
        }
        return left + right;
    }
}
