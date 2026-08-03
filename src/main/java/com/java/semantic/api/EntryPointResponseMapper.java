package com.java.semantic.api;

import com.java.semantic.api.dto.ApiEntryPointMethodResponse;
import com.java.semantic.api.dto.EntryPointClassResponse;
import com.java.semantic.api.dto.EntryPointMethodResponse;
import com.java.semantic.api.dto.EntryPointsResponse;
import com.java.semantic.api.dto.MethodTargetResolutionResponse;
import com.java.semantic.api.dto.MqEntryPointMethodResponse;
import com.java.semantic.api.dto.ScheduleEntryPointMethodResponse;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.syntax.application.RevisionBoundEntryPoints;
import com.java.semantic.syntax.domain.ApiEntryPoint;
import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.EntryPointMethod;
import com.java.semantic.syntax.domain.MqEntryPoint;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.ScheduleEntryPoint;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.application.DiscoveryFollowUpFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Component
public final class EntryPointResponseMapper {

    public EntryPointsResponse toResponse(RevisionBoundEntryPoints result) {
        Objects.requireNonNull(result, "result is required");
        return new EntryPointsResponse(
                result.repositoryId().value(),
                result.analyzedRevision().value(),
                result.entryPoints().stream()
                        .map(entryPoint -> toResponse(result.repositoryId(), result.analyzedRevision(), entryPoint))
                        .toList());
    }

    private EntryPointClassResponse toResponse(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            EntryPointClass entryPointClass) {
        Objects.requireNonNull(entryPointClass, "entryPointClass is required");
        return new EntryPointClassResponse(
                JavaSourceIdentityHttpMapper.toPayload(entryPointClass.sourceType()),
                entryPointClass.description(),
                entryPointClass.basePaths(),
                entryPointClass.methods().stream().map(method -> toResponse(repositoryId, revision, method)).toList());
    }

    private EntryPointMethodResponse toResponse(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            EntryPointMethod method) {
        Objects.requireNonNull(method, "method is required");
        if (method instanceof ApiEntryPoint apiEntryPoint) {
            return new ApiEntryPointMethodResponse(
                    apiEntryPoint.name(),
                    apiEntryPoint.description(),
                    apiEntryPoint.type(),
                    apiEntryPoint.apiUrl(),
                    apiEntryPoint.httpMethods(),
                    apiEntryPoint.swaggerDescriptions(),
                    toResponse(repositoryId, revision, apiEntryPoint.analysisTarget()));
        }
        if (method instanceof MqEntryPoint mqEntryPoint) {
            return new MqEntryPointMethodResponse(
                    mqEntryPoint.name(),
                    mqEntryPoint.description(),
                    mqEntryPoint.type(),
                    mqEntryPoint.broker(),
                    mqEntryPoint.destinations(),
                    toResponse(repositoryId, revision, mqEntryPoint.analysisTarget()));
        }
        if (method instanceof ScheduleEntryPoint scheduleEntryPoint) {
            return new ScheduleEntryPointMethodResponse(
                    scheduleEntryPoint.name(),
                    scheduleEntryPoint.description(),
                    scheduleEntryPoint.type(),
                    scheduleEntryPoint.triggerKind(),
                    scheduleEntryPoint.triggerValue(),
                    toResponse(repositoryId, revision, scheduleEntryPoint.analysisTarget()));
        }
        throw new IllegalArgumentException("unsupported entry point method");
    }

    private final DiscoveryFollowUpFactory followUpFactory;
    private final StructuredDiscoveryResponseMapper followUpMapper;

    public EntryPointResponseMapper(
            DiscoveryFollowUpFactory followUpFactory,
            StructuredDiscoveryResponseMapper followUpMapper) {
        this.followUpFactory = Objects.requireNonNull(followUpFactory, "followUpFactory is required");
        this.followUpMapper = Objects.requireNonNull(followUpMapper, "followUpMapper is required");
    }

    MethodTargetResolutionResponse toResponse(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            MethodTargetResolution resolution) {
        Objects.requireNonNull(resolution, "resolution is required");
        return switch (resolution.status()) {
            case RESOLVED -> new MethodTargetResolutionResponse(
                    resolution.status().name(),
                    JavaSourceIdentityHttpMapper.toPayload(resolution.target().orElseThrow()),
                    List.of(),
                    resolution.reasonCode(),
                    resolution.target().stream()
                            .flatMap(target -> followUpFactory.forMethod(repositoryId, revision, target).stream())
                            .map(followUpMapper::followUp).toList());
            case UNRESOLVED -> new MethodTargetResolutionResponse(
                    resolution.status().name(),
                    null,
                    List.of(),
                    resolution.reasonCode(),
                    List.of());
            case AMBIGUOUS -> new MethodTargetResolutionResponse(
                    resolution.status().name(),
                    null,
                    resolution.candidates().stream().sorted(METHOD_TARGET_COMPARATOR).map(JavaSourceIdentityHttpMapper::toPayload)
                            .toList(),
                    resolution.reasonCode(),
                    resolution.candidates().stream()
                            .sorted(METHOD_TARGET_COMPARATOR)
                            .flatMap(target -> followUpFactory.methodSourceOnly(repositoryId, revision, target).stream())
                            .map(followUpMapper::followUp).toList());
        };
    }

    private static final Comparator<MethodTarget> METHOD_TARGET_COMPARATOR = Comparator
            .comparing(MethodTarget::sourceFile)
            .thenComparing(MethodTarget::packageName)
            .thenComparing(MethodTarget::className)
            .thenComparing(MethodTarget::methodName)
            .thenComparing(MethodTarget::parameterTypes, EntryPointResponseMapper::compareParameterTypes);

    private static int compareParameterTypes(List<String> left, List<String> right) {
        int length = Math.min(left.size(), right.size());
        for (int index = 0; index < length; index++) {
            int comparison = left.get(index).compareTo(right.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.size(), right.size());
    }
}
