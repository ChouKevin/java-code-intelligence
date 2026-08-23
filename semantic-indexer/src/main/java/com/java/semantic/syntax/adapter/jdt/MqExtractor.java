package com.java.semantic.syntax.adapter.jdt;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import com.java.semantic.syntax.domain.MqBroker;
import com.java.semantic.syntax.domain.MqEntryPoint;
import com.java.semantic.syntax.domain.MethodTargetResolution;

import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.MethodDeclaration;

/**
 * 從型別宣告抽取訊息佇列消費者
 * <p>
 * 支援範圍等同 call graph 分類器既有的集合：RabbitMQ 與 Kafka
 */
final class MqExtractor {

    private static final String DEPRECATED = "Deprecated";

    /**
     * 每個 broker 的註解名稱與目的地屬性優先序
     * <p>
     * Kafka 的目的地寫在 topics，與 Rabbit 的 queues 不同，舊分析器兩者都沒處理
     */
    private static final List<BrokerBinding> BINDINGS = List.of(
            new BrokerBinding(MqBroker.RABBIT, "RabbitListener", new String[]{"queues", "value"}),
            new BrokerBinding(MqBroker.KAFKA, "KafkaListener", new String[]{"topics", "value"}));

    private record BrokerBinding(MqBroker broker, String annotation, String[] destinationAttributes) {
    }

    private MqExtractor() {
    }

    static List<MqEntryPoint> extract(
            AbstractTypeDeclaration type,
            Function<MethodDeclaration, MethodTargetResolution> analysisTargetOf) {
        if (!SourceTypes.isEntryPointCandidate(type)) {
            return List.of();
        }

        List<MqEntryPoint> entryPoints = new ArrayList<>();
        for (MethodDeclaration method : SourceTypes.declaredMethodsOf(type)) {
            if (AnnotationReader.isPresent(method, DEPRECATED)) {
                continue;
            }
            toEntryPoint(method, analysisTargetOf).ifPresent(entryPoints::add);
        }
        return List.copyOf(entryPoints);
    }

    private static Optional<MqEntryPoint> toEntryPoint(
            MethodDeclaration method,
            Function<MethodDeclaration, MethodTargetResolution> analysisTargetOf) {
        for (BrokerBinding binding : BINDINGS) {
            Optional<Annotation> listener = AnnotationReader.find(method, binding.annotation());
            if (listener.isEmpty()) {
                continue;
            }
            List<String> destinations = AnnotationReader.stringValues(
                    listener.get(), binding.destinationAttributes());
            return Optional.of(new MqEntryPoint(
                    method.getName().getIdentifier(),
                    JavadocReader.descriptionOf(method),
                    binding.broker(),
                    destinations,
                    analysisTargetOf.apply(method)));
        }
        return Optional.empty();
    }
}
