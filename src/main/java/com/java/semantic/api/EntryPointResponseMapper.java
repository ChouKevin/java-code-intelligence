package com.java.semantic.api;

import com.java.semantic.api.dto.ApiEntryPointMethodResponse;
import com.java.semantic.api.dto.EntryPointClassResponse;
import com.java.semantic.api.dto.EntryPointMethodResponse;
import com.java.semantic.api.dto.EntryPointsResponse;
import com.java.semantic.api.dto.MqEntryPointMethodResponse;
import com.java.semantic.api.dto.ScheduleEntryPointMethodResponse;
import com.java.semantic.syntax.application.RevisionBoundEntryPoints;
import com.java.semantic.syntax.domain.ApiEntryPoint;
import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.EntryPointMethod;
import com.java.semantic.syntax.domain.MqEntryPoint;
import com.java.semantic.syntax.domain.ScheduleEntryPoint;
import org.springframework.stereotype.Component;

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
                    apiEntryPoint.swaggerDescriptions());
        }
        if (method instanceof MqEntryPoint mqEntryPoint) {
            return new MqEntryPointMethodResponse(
                    mqEntryPoint.name(),
                    mqEntryPoint.description(),
                    mqEntryPoint.type(),
                    mqEntryPoint.broker(),
                    mqEntryPoint.destinations());
        }
        if (method instanceof ScheduleEntryPoint scheduleEntryPoint) {
            return new ScheduleEntryPointMethodResponse(
                    scheduleEntryPoint.name(),
                    scheduleEntryPoint.description(),
                    scheduleEntryPoint.type(),
                    scheduleEntryPoint.triggerKind(),
                    scheduleEntryPoint.triggerValue());
        }
        throw new IllegalArgumentException("unsupported entry point method");
    }
}
