package com.java.semantic.syntax.application.concept;

import java.util.Objects;
import java.util.Optional;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.syntax.domain.MqBroker;
import com.java.semantic.syntax.domain.ScheduleTriggerKind;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/** entry-point 類概念的唯一識別，供 API_ROUTE、MQ_DESTINATION 與 SCHEDULE 以穩定值合併目錄 */
public sealed interface EntryPointConceptIdentity extends ConceptIdentity permits
        EntryPointConceptIdentity.ApiRouteConceptIdentity,
        EntryPointConceptIdentity.MqDestinationConceptIdentity,
        EntryPointConceptIdentity.ScheduleConceptIdentity {

    /** API_ROUTE 識別保存 target、HTTP verb 與 route，transport target 與 subject 由此衍生 */
    record ApiRouteConceptIdentity(MethodTarget target, String httpVerb, String route)
            implements EntryPointConceptIdentity {

        public ApiRouteConceptIdentity {
            target = Objects.requireNonNull(target, "target is required");
            httpVerb = requiredText(httpVerb, "httpVerb");
            route = requiredText(route, "route");
        }

        @Override
        public ConceptKind kind() {
            return ConceptKind.API_ROUTE;
        }

        @Override
        public ConceptIdentityKind identityKind() {
            return ConceptIdentityKind.API_ROUTE;
        }
    }

    /** MQ_DESTINATION 識別保存 target、broker 與 destination，transport target 與 subject 由此衍生 */
    record MqDestinationConceptIdentity(MethodTarget target, MqBroker broker, String destination)
            implements EntryPointConceptIdentity {

        public MqDestinationConceptIdentity {
            target = Objects.requireNonNull(target, "target is required");
            broker = Objects.requireNonNull(broker, "broker is required");
            destination = requiredText(destination, "destination");
        }

        @Override
        public ConceptKind kind() {
            return ConceptKind.MQ_DESTINATION;
        }

        @Override
        public ConceptIdentityKind identityKind() {
            return ConceptIdentityKind.MQ_DESTINATION;
        }
    }

    /** SCHEDULE 識別保存 target 與 trigger，transport target 與 subject 由此衍生 */
    record ScheduleConceptIdentity(
            MethodTarget target,
            ScheduleTriggerKind triggerKind,
            Optional<String> triggerValue) implements EntryPointConceptIdentity {

        public ScheduleConceptIdentity {
            target = Objects.requireNonNull(target, "target is required");
            triggerKind = Objects.requireNonNull(triggerKind, "triggerKind is required");
            triggerValue = Objects.requireNonNull(triggerValue, "triggerValue is required");
        }

        @Override
        public ConceptKind kind() {
            return ConceptKind.SCHEDULE;
        }

        @Override
        public ConceptIdentityKind identityKind() {
            return ConceptIdentityKind.SCHEDULE;
        }
    }

    private static String requiredText(String value, String fieldName) {
        String text = Objects.requireNonNull(value, fieldName + " is required");
        Assert.isTrue(StringUtils.hasText(text), fieldName + " is required");
        return text;
    }
}
