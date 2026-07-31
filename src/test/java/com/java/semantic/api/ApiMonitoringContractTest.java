package com.java.semantic.api;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 驗證 HTTP DTO 的監控欄位分類為明確且安全的契約 */
class ApiMonitoringContractTest {

    private static final String API_PACKAGE = "com.java.semantic.api";

    private static final String DTO_PACKAGE = "com.java.semantic.api.dto";

    @Test
    void should_explicitly_classify_every_project_owned_dto_record_component() {
        List<String> violations = new ArrayList<>();
        for (RecordComponent component : dtoRecordComponents()) {
            ApiMonitoringField annotation = component.getAnnotation(ApiMonitoringField.class);
            String componentId = component.getDeclaringRecord().getSimpleName() + "." + component.getName();
            if (Objects.isNull(annotation)) {
                violations.add(componentId + " is missing ApiMonitoringField");
                continue;
            }
            verifyClassification(component, annotation.value(), componentId, violations);
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void should_accept_only_project_owned_records_or_closed_record_only_nested_hierarchies() {
        assertThat(isSupportedNested(component(DirectRecordPayload.class))).isTrue();
        assertThat(isSupportedNested(component(ClosedInterfacePayload.class))).isTrue();
        assertThat(isSupportedNested(component(OptionalClosedInterfacePayload.class))).isTrue();
        assertThat(isSupportedNested(component(NestedOptionalPayload.class))).isFalse();
        assertThat(isSupportedNested(component(OpenInterfacePayload.class))).isFalse();
        assertThat(isSupportedNested(component(NonRecordPermittedPayload.class))).isFalse();
    }

    private static RecordComponent component(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .filter(component -> "nested".equals(component.getName()))
                .findFirst()
                .orElseThrow();
    }

    private static List<RecordComponent> dtoRecordComponents() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(DTO_PACKAGE)
                .stream()
                .map(JavaClass::getName)
                .map(ApiMonitoringContractTest::loadClass)
                .filter(Class::isRecord)
                .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                .sorted(Comparator.comparing(component -> component.getDeclaringRecord().getName()
                        + "." + component.getName()))
                .toList();
    }

    private static Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("compiled DTO class is unavailable", exception);
        }
    }

    private static void verifyClassification(
            RecordComponent component,
            ApiMonitoringMode mode,
            String componentId,
            List<String> violations) {
        if ("sourceFile".equals(component.getName()) && mode != ApiMonitoringMode.VALUE) {
            violations.add(componentId + " must use VALUE for a repository-relative source file");
        }
        if (Collection.class.isAssignableFrom(component.getType()) && mode != ApiMonitoringMode.SIZE) {
            violations.add(componentId + " must use SIZE for a collection");
        }
        if (isSensitiveContent(component.getName()) && mode != ApiMonitoringMode.OMIT) {
            violations.add(componentId + " must use OMIT for sensitive content");
        }
        if (mode == ApiMonitoringMode.VALUE && !isSupportedValue(component.getGenericType())) {
            violations.add(componentId + " VALUE is unsupported for " + component.getType().getSimpleName());
        }
        if (mode == ApiMonitoringMode.SIZE && !isSupportedSize(component.getType())) {
            violations.add(componentId + " SIZE is unsupported for " + component.getType().getSimpleName());
        }
        if (mode == ApiMonitoringMode.NESTED && !isSupportedNested(component)) {
            violations.add(componentId + " NESTED requires a project-owned DTO record");
        }
    }

    private static boolean isSensitiveContent(String componentName) {
        return "source".equals(componentName)
                || "sql".equals(componentName)
                || "xml".equals(componentName)
                || "content".equals(componentName)
                || "requestBody".equals(componentName)
                || "authorization".equals(componentName)
                || "credentials".equals(componentName)
                || "callExpression".equals(componentName);
    }

    private static boolean isSupportedValue(Type valueType) {
        if (valueType instanceof Class<?> valueClass) {
            return isSupportedScalarValue(valueClass);
        }
        if (!(valueType instanceof ParameterizedType parameterizedType)
                || parameterizedType.getRawType() != Optional.class) {
            return false;
        }
        Type[] typeArguments = parameterizedType.getActualTypeArguments();
        return typeArguments.length == 1
                && typeArguments[0] instanceof Class<?> scalarClass
                && isSupportedScalarValue(scalarClass);
    }

    private static boolean isSupportedScalarValue(Class<?> type) {
        return type == String.class
                || type == Integer.class
                || type == Long.class
                || type == Boolean.class
                || type == int.class
                || type == long.class
                || type == boolean.class
                || type.isEnum();
    }

    private static boolean isSupportedSize(Class<?> type) {
        return CharSequence.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type);
    }

    private static boolean isSupportedNested(RecordComponent component) {
        return isSupportedNestedType(component.getGenericType());
    }

    private static boolean isSupportedNestedType(Type nestedType) {
        if (nestedType instanceof Class<?> nestedClass) {
            return isSupportedNestedClass(nestedClass);
        }
        if (!(nestedType instanceof ParameterizedType parameterizedType)
                || parameterizedType.getRawType() != Optional.class) {
            return false;
        }
        Type[] typeArguments = parameterizedType.getActualTypeArguments();
        return typeArguments.length == 1
                && typeArguments[0] instanceof Class<?> nestedClass
                && isSupportedNestedClass(nestedClass);
    }

    private static boolean isSupportedNestedClass(Class<?> nestedClass) {
        if (!isProjectOwnedApiType(nestedClass)) {
            return false;
        }
        if (nestedClass.isRecord()) {
            return true;
        }
        if (!nestedClass.isInterface() || !nestedClass.isSealed()) {
            return false;
        }
        Class<?>[] permittedSubclasses = nestedClass.getPermittedSubclasses();
        return permittedSubclasses.length > 0
                && Arrays.stream(permittedSubclasses)
                        .allMatch(ApiMonitoringContractTest::isSupportedNestedClass);
    }

    private static boolean isProjectOwnedApiType(Class<?> type) {
        Package typePackage = type.getPackage();
        String packageName = typePackage.getName();
        return API_PACKAGE.equals(packageName) || packageName.startsWith(API_PACKAGE + ".");
    }

    private record DirectNestedRecord(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String identifier) {
    }

    private sealed interface ClosedNested permits ClosedNestedRecord {
    }

    private record ClosedNestedRecord(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String identifier) implements ClosedNested {
    }

    private interface OpenNested {
    }

    private sealed interface NonRecordPermittedNested permits NonRecordNested {
    }

    private static final class NonRecordNested implements NonRecordPermittedNested {
    }

    private record DirectRecordPayload(
            @ApiMonitoringField(ApiMonitoringMode.NESTED) DirectNestedRecord nested) {
    }

    private record ClosedInterfacePayload(
            @ApiMonitoringField(ApiMonitoringMode.NESTED) ClosedNested nested) {
    }

    private record OptionalClosedInterfacePayload(
            @ApiMonitoringField(ApiMonitoringMode.NESTED) Optional<ClosedNested> nested) {
    }

    private record NestedOptionalPayload(
            @ApiMonitoringField(ApiMonitoringMode.NESTED) Optional<Optional<DirectNestedRecord>> nested) {
    }

    private record OpenInterfacePayload(
            @ApiMonitoringField(ApiMonitoringMode.NESTED) OpenNested nested) {
    }

    private record NonRecordPermittedPayload(
            @ApiMonitoringField(ApiMonitoringMode.NESTED) NonRecordPermittedNested nested) {
    }
}
