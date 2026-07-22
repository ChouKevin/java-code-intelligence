package com.java.semantic.api;

import com.java.semantic.api.dto.ApiEntryPointMethodResponse;
import com.java.semantic.api.dto.EntryPointClassResponse;
import com.java.semantic.api.dto.EntryPointMethodResponse;
import com.java.semantic.api.dto.EntryPointsResponse;
import com.java.semantic.api.dto.MethodTargetResolutionResponse;
import com.java.semantic.api.dto.MethodTargetResponse;
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
                result.entryPoints().stream().map(this::toResponse).toList());
    }

    private EntryPointClassResponse toResponse(EntryPointClass entryPointClass) {
        Objects.requireNonNull(entryPointClass, "entryPointClass is required");
        return new EntryPointClassResponse(
                entryPointClass.className(),
                entryPointClass.packageName(),
                entryPointClass.packagePath(),
                entryPointClass.description(),
                entryPointClass.basePaths(),
                entryPointClass.methods().stream().map(this::toResponse).toList());
    }

    private EntryPointMethodResponse toResponse(EntryPointMethod method) {
        Objects.requireNonNull(method, "method is required");
        if (method instanceof ApiEntryPoint apiEntryPoint) {
            return new ApiEntryPointMethodResponse(
                    apiEntryPoint.name(),
                    apiEntryPoint.description(),
                    apiEntryPoint.type(),
                    apiEntryPoint.apiUrl(),
                    apiEntryPoint.httpMethods(),
                    apiEntryPoint.swaggerDescriptions(),
                    toResponse(apiEntryPoint.analysisTarget()));
        }
        if (method instanceof MqEntryPoint mqEntryPoint) {
            return new MqEntryPointMethodResponse(
                    mqEntryPoint.name(),
                    mqEntryPoint.description(),
                    mqEntryPoint.type(),
                    mqEntryPoint.broker(),
                    mqEntryPoint.destinations(),
                    toResponse(mqEntryPoint.analysisTarget()));
        }
        if (method instanceof ScheduleEntryPoint scheduleEntryPoint) {
            return new ScheduleEntryPointMethodResponse(
                    scheduleEntryPoint.name(),
                    scheduleEntryPoint.description(),
                    scheduleEntryPoint.type(),
                    scheduleEntryPoint.triggerKind(),
                    scheduleEntryPoint.triggerValue(),
                    toResponse(scheduleEntryPoint.analysisTarget()));
        }
        throw new IllegalArgumentException("unsupported entry point method");
    }

    static MethodTargetResolutionResponse toResponse(MethodTargetResolution resolution) {
        Objects.requireNonNull(resolution, "resolution is required");
        return switch (resolution.status()) {
            case RESOLVED -> new MethodTargetResolutionResponse(
                    resolution.status().name(),
                    targetResponse(resolution.target().orElseThrow()),
                    List.of(),
                    resolution.reasonCode());
            case UNRESOLVED -> new MethodTargetResolutionResponse(
                    resolution.status().name(),
                    null,
                    List.of(),
                    resolution.reasonCode());
            case AMBIGUOUS -> new MethodTargetResolutionResponse(
                    resolution.status().name(),
                    null,
                    resolution.candidates().stream().sorted(METHOD_TARGET_COMPARATOR).map(EntryPointResponseMapper::targetResponse)
                            .toList(),
                    resolution.reasonCode());
        };
    }

    private static final Comparator<MethodTarget> METHOD_TARGET_COMPARATOR = Comparator
            .comparing(MethodTarget::sourceFile)
            .thenComparing(MethodTarget::packageName)
            .thenComparing(MethodTarget::className)
            .thenComparing(MethodTarget::methodName)
            .thenComparing(MethodTarget::parameterTypes, EntryPointResponseMapper::compareParameterTypes);

    private static MethodTargetResponse targetResponse(MethodTarget target) {
        return new MethodTargetResponse(
                target.sourceFile(),
                target.packageName(),
                target.className(),
                target.methodName(),
                target.parameterTypes());
    }

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
