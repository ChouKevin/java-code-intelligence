package com.java.semantic.syntax.application;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.syntax.application.ConceptIdentity.ApiRouteConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.MqDestinationConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.ScheduleConceptIdentity;
import com.java.semantic.syntax.domain.AnalysisTargetStatus;
import com.java.semantic.syntax.domain.ApiEntryPoint;
import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.EntryPointMethod;
import com.java.semantic.syntax.domain.MqEntryPoint;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.ScheduleEntryPoint;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** 從既有 entry-point metadata 投影 API_ROUTE、MQ_DESTINATION 與 SCHEDULE 概念 */
public final class EntryPointConceptProvider implements ConceptProvider {

    private static final String PROVIDER_ID = "entry-point";

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public Set<ConceptKind> supportedKinds() {
        return Set.copyOf(EnumSet.of(ConceptKind.API_ROUTE, ConceptKind.MQ_DESTINATION, ConceptKind.SCHEDULE));
    }

    @Override
    public ConceptProviderProjection project(RepositorySyntax syntax) {
        List<ConceptCatalogEntry> entries = new ArrayList<>();
        List<ConceptIssueReason> issues = new ArrayList<>();
        for (EntryPointClass entryPointClass : syntax.entryPoints()) {
            for (EntryPointMethod method : entryPointClass.methods()) {
                Optional<MethodTarget> target = resolvedTarget(method);
                if (target.isPresent()) {
                    MethodTarget resolvedTarget = target.orElseThrow();
                    if (method instanceof ApiEntryPoint api) {
                        addApiRoutes(entries, entryPointClass, api, resolvedTarget);
                    }
                    if (method instanceof MqEntryPoint mq) {
                        addMqDestinations(entries, issues, entryPointClass, mq, resolvedTarget);
                    }
                    if (method instanceof ScheduleEntryPoint schedule) {
                        addSchedule(entries, issues, entryPointClass, schedule, resolvedTarget);
                    }
                }
            }
        }
        return new ConceptProviderProjection(entries, issues);
    }

    private static Optional<MethodTarget> resolvedTarget(EntryPointMethod method) {
        if (method.analysisTarget().status() == AnalysisTargetStatus.RESOLVED) {
            return method.analysisTarget().target();
        }
        return Optional.empty();
    }

    private static void addApiRoutes(
            List<ConceptCatalogEntry> entries,
            EntryPointClass entryPointClass,
            ApiEntryPoint api,
            MethodTarget target) {
        for (String httpMethod : api.httpMethods()) {
            if (StringUtils.hasText(httpMethod) && StringUtils.hasText(api.apiUrl())
                    && !isUnresolvedMetadataValue(api.apiUrl())) {
                String verb = httpMethod.toUpperCase(Locale.ROOT);
                String route = api.apiUrl();
                ApiRouteConceptIdentity identity = new ApiRouteConceptIdentity(target, verb, route);
                entries.add(entry(identity, verb + " " + route, entryPointClass, api.name(),
                        ConceptAuthority.FRAMEWORK_METADATA, route + " " + target.className() + " " + target.methodName()));
            }
        }
    }

    private static void addMqDestinations(
            List<ConceptCatalogEntry> entries,
            List<ConceptIssueReason> issues,
            EntryPointClass entryPointClass,
            MqEntryPoint mq,
            MethodTarget target) {
        List<String> destinations = mq.destinations().stream().filter(StringUtils::hasText).toList();
        if (destinations.isEmpty()) {
            issues.add(ConceptIssueReason.MQ_DESTINATION_UNRESOLVED);
            return;
        }
        for (String destination : destinations) {
            if (isUnresolvedMetadataValue(destination)) {
                issues.add(ConceptIssueReason.MQ_DESTINATION_UNRESOLVED);
                continue;
            }
            MqDestinationConceptIdentity identity = new MqDestinationConceptIdentity(target, mq.broker(), destination);
            entries.add(entry(identity, mq.broker() + ":" + destination, entryPointClass, mq.name(),
                    ConceptAuthority.FRAMEWORK_METADATA,
                    mq.broker() + " " + destination + " " + target.className() + " " + target.methodName()));
        }
    }

    private static boolean isUnresolvedMetadataValue(String value) {
        return value.contains("${") || value.contains("#{");
    }

    private static void addSchedule(
            List<ConceptCatalogEntry> entries,
            List<ConceptIssueReason> issues,
            EntryPointClass entryPointClass,
            ScheduleEntryPoint schedule,
            MethodTarget target) {
        Optional<String> triggerValue = StringUtils.hasText(schedule.triggerValue())
                && !isUnresolvedMetadataValue(schedule.triggerValue())
                ? Optional.of(schedule.triggerValue())
                : Optional.empty();
        if (triggerValue.isEmpty()) {
            issues.add(ConceptIssueReason.SCHEDULE_TRIGGER_VALUE_UNRESOLVED);
        }
        ScheduleConceptIdentity identity = new ScheduleConceptIdentity(target, schedule.triggerKind(), triggerValue);
        String displayTriggerValue = triggerValue.orElse("<unresolved>");
        entries.add(entry(identity,
                schedule.triggerKind() + ":" + displayTriggerValue,
                entryPointClass,
                schedule.name(),
                triggerValue.isPresent() ? ConceptAuthority.FRAMEWORK_METADATA : ConceptAuthority.METADATA_VALUE_UNRESOLVED,
                schedule.triggerKind() + " " + triggerValue.orElse("") + " "
                        + target.className() + " " + target.methodName()));
    }

    private static ConceptCatalogEntry entry(
            ConceptIdentity identity,
            String canonicalValue,
            EntryPointClass entryPointClass,
            String methodName,
            ConceptAuthority authority,
            String tokenSource) {
        return new ConceptCatalogEntry(
                PROVIDER_ID,
                identity,
                canonicalValue,
                entryPointClass.className() + "." + methodName,
                ConceptSearchTokenizer.tokenize(tokenSource),
                entryPointClass.packageName(),
                Optional.of(qualifiedType(entryPointClass)),
                authority,
                Set.of(identity));
    }

    private static String qualifiedType(EntryPointClass entryPointClass) {
        return entryPointClass.packageName().isEmpty()
                ? entryPointClass.className()
                : entryPointClass.packageName() + "." + entryPointClass.className();
    }
}
