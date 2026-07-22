package com.java.semantic.syntax.adapter.jdt;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import com.java.semantic.syntax.domain.ScheduleEntryPoint;
import com.java.semantic.syntax.domain.ScheduleTriggerKind;
import com.java.semantic.syntax.domain.MethodTargetResolution;

import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.MethodDeclaration;

/**
 * 從型別宣告抽取排程任務
 * <p>
 * 支援範圍等同 call graph 分類器既有的集合：@Scheduled 與 XXL-Job
 */
final class ScheduleExtractor {

    private static final String DEPRECATED = "Deprecated";

    private static final String SCHEDULED = "Scheduled";

    /** XXL-Job 兩種寫法 */
    private static final List<String> JOB_ANNOTATIONS = List.of("XxlJob", "Job");

    /**
     * @Scheduled 觸發設定的真正優先序
     * <p>
     * cron 最明確故居首；數值變體與字串變體同權，舊分析器只讀 *String 變體
     */
    private static final List<TriggerBinding> TRIGGERS = List.of(
            new TriggerBinding(ScheduleTriggerKind.CRON, "cron", false),
            new TriggerBinding(ScheduleTriggerKind.FIXED_DELAY, "fixedDelayString", false),
            new TriggerBinding(ScheduleTriggerKind.FIXED_DELAY, "fixedDelay", true),
            new TriggerBinding(ScheduleTriggerKind.FIXED_RATE, "fixedRateString", false),
            new TriggerBinding(ScheduleTriggerKind.FIXED_RATE, "fixedRate", true));

    private record TriggerBinding(ScheduleTriggerKind kind, String attribute, boolean numeric) {
    }

    private ScheduleExtractor() {
    }

    static List<ScheduleEntryPoint> extract(
            AbstractTypeDeclaration type,
            Function<MethodDeclaration, MethodTargetResolution> analysisTargetOf) {
        if (!SourceTypes.isEntryPointCandidate(type)) {
            return List.of();
        }

        List<ScheduleEntryPoint> entryPoints = new ArrayList<>();
        for (MethodDeclaration method : SourceTypes.declaredMethodsOf(type)) {
            if (AnnotationReader.isPresent(method, DEPRECATED)) {
                continue;
            }
            toEntryPoint(method, analysisTargetOf).ifPresent(entryPoints::add);
        }
        return List.copyOf(entryPoints);
    }

    private static Optional<ScheduleEntryPoint> toEntryPoint(
            MethodDeclaration method,
            Function<MethodDeclaration, MethodTargetResolution> analysisTargetOf) {
        Optional<Annotation> scheduled = AnnotationReader.find(method, SCHEDULED);
        if (scheduled.isPresent()) {
            return Optional.of(fromScheduled(method, scheduled.get(), analysisTargetOf));
        }
        return AnnotationReader.findAny(method, JOB_ANNOTATIONS)
                .map(job -> new ScheduleEntryPoint(
                        method.getName().getIdentifier(),
                        JavadocReader.descriptionOf(method),
                        ScheduleTriggerKind.JOB_HANDLER,
                        AnnotationReader.stringValue(job, "value").orElse(""),
                        analysisTargetOf.apply(method)));
    }

    private static ScheduleEntryPoint fromScheduled(
            MethodDeclaration method,
            Annotation scheduled,
            Function<MethodDeclaration, MethodTargetResolution> analysisTargetOf) {
        String name = method.getName().getIdentifier();
        String description = JavadocReader.descriptionOf(method);

        for (TriggerBinding trigger : TRIGGERS) {
            Optional<String> value = trigger.numeric()
                    ? AnnotationReader.numberValue(scheduled, trigger.attribute()).map(String::valueOf)
                    : AnnotationReader.stringValue(scheduled, trigger.attribute());
            if (value.isPresent()) {
                return new ScheduleEntryPoint(name, description, trigger.kind(), value.get(), analysisTargetOf.apply(method));
            }
        }
        return new ScheduleEntryPoint(
                name, description, ScheduleTriggerKind.UNSPECIFIED, "", analysisTargetOf.apply(method));
    }
}
