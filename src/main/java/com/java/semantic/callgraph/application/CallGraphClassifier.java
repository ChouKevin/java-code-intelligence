package com.java.semantic.callgraph.application;

import com.java.semantic.callgraph.domain.CallType;
import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.ClassMetadata.FieldInfo;
import com.java.semantic.syntax.domain.ClassMetadata.MethodSignature;
import com.java.semantic.syntax.domain.ClassMetadata.TypeKind;

import java.beans.Introspector;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Classifies graph targets from syntax metadata without adapter dependencies. */
public final class CallGraphClassifier {

    private static final Set<String> CONTROLLERS = Set.of("Controller", "RestController");
    private static final Set<String> SERVICES = Set.of("Service");
    private static final Set<String> COMPONENTS = Set.of("Component", "Configuration");
    private static final Set<String> FEIGN = Set.of("FeignClient");
    private static final Set<String> DATA_ACCESS = Set.of("Mapper", "Repository");
    private static final Set<String> DATA_ACCESS_TYPES = Set.of(
            "BaseMapper", "JpaRepository", "CrudRepository", "PagingAndSortingRepository",
            "MongoRepository", "R2dbcRepository");
    private static final Set<String> LOMBOK_GETTERS = Set.of("Data", "Getter", "Value");
    private static final Set<String> LOMBOK_SETTERS = Set.of("Data", "Setter");
    private static final Set<String> LOMBOK_OBJECT_METHODS = Set.of("Data", "Value");
    private static final Set<String> LOMBOK_BUILDERS = Set.of("Builder", "SuperBuilder");
    private static final Set<String> LOMBOK_FLUENT_ACCESSORS = Set.of("Data", "Getter", "Setter", "Value");
    private static final Set<String> GENERATED_OBJECT_METHODS = Set.of("toString", "equals", "hashCode");

    public CallType callType(ClassMetadata metadata, String methodName) {
        return callType(metadata, methodName, List.of(), Optional.empty());
    }

    public CallType callType(
            ClassMetadata metadata,
            String methodName,
            List<String> parameterTypes,
            Optional<ClassMetadata> builderOwner) {
        return callType(metadata, methodName, parameterTypes, "", builderOwner);
    }

    public CallType callType(
            ClassMetadata metadata,
            String methodName,
            List<String> parameterTypes,
            String returnType,
            Optional<ClassMetadata> builderOwner) {
        if (isLombokGenerated(metadata, methodName, parameterTypes, returnType, builderOwner)) {
            return CallType.GENERATED_CODE;
        }
        if (matchesAny(metadata.annotations(), CONTROLLERS)) {
            return CallType.INTERNAL_CONTROLLER;
        }
        if (matchesAny(metadata.annotations(), SERVICES)) {
            return CallType.INTERNAL_SERVICE;
        }
        if (matchesAny(metadata.annotations(), FEIGN)) {
            return CallType.RPC_CLIENT;
        }
        if (isDataAccess(metadata)) {
            return CallType.DATA_ACCESS;
        }
        if (matchesAny(metadata.annotations(), COMPONENTS)) {
            return CallType.INTERNAL_COMPONENT;
        }
        if (TypeKind.INTERFACE.equals(metadata.kind())) {
            return CallType.INTERFACE;
        }
        return CallType.INTERNAL_CLASS;
    }

    public ResolutionStrategy resolutionStrategy(ClassMetadata metadata, String methodName) {
        return resolutionStrategy(metadata, methodName, List.of(), Optional.empty());
    }

    public ResolutionStrategy resolutionStrategy(
            ClassMetadata metadata,
            String methodName,
            List<String> parameterTypes,
            Optional<ClassMetadata> builderOwner) {
        return resolutionStrategy(metadata, methodName, parameterTypes, "", builderOwner);
    }

    public ResolutionStrategy resolutionStrategy(
            ClassMetadata metadata,
            String methodName,
            List<String> parameterTypes,
            String returnType,
            Optional<ClassMetadata> builderOwner) {
        if (isLombokGenerated(metadata, methodName, parameterTypes, returnType, builderOwner)) {
            return ResolutionStrategy.LOMBOK_GENERATED;
        }
        if (matchesAny(metadata.annotations(), FEIGN)) {
            return ResolutionStrategy.FEIGN_CLIENT;
        }
        if (isDataAccess(metadata)) {
            List<MethodSignature> matchingMethods = metadata.methods().stream()
                    .filter(method -> method.name().equals(methodName))
                    .toList();
            boolean hasSql = matchingMethods.size() == 1
                    && Optional.ofNullable(matchingMethods.get(0).sqlSource()).isPresent();
            return hasSql
                    ? ResolutionStrategy.MYBATIS_MAPPER
                    : ResolutionStrategy.DATA_ACCESS_WITHOUT_EVIDENCE;
        }
        return ResolutionStrategy.JDT_CALL_HIERARCHY;
    }

    public ResolutionStrategy resolutionStrategy(ClassMetadata metadata, MethodSignature method) {
        return resolutionStrategy(metadata, method, Optional.empty());
    }

    public ResolutionStrategy resolutionStrategy(
            ClassMetadata metadata,
            MethodSignature method,
            Optional<ClassMetadata> builderOwner) {
        String returnType = method.returnType()
                .map(reference -> reference.writtenType())
                .orElse("");
        return resolutionStrategy(metadata, method, returnType, builderOwner);
    }

    public ResolutionStrategy resolutionStrategy(
            ClassMetadata metadata,
            MethodSignature method,
            String returnType,
            Optional<ClassMetadata> builderOwner) {
        if (isLombokGenerated(metadata, method.name(), method.paramTypes(), returnType, builderOwner)) {
            return ResolutionStrategy.LOMBOK_GENERATED;
        }
        if (matchesAny(metadata.annotations(), FEIGN)) {
            return ResolutionStrategy.FEIGN_CLIENT;
        }
        if (isDataAccess(metadata)) {
            return Optional.ofNullable(method.sqlSource()).isPresent()
                    ? ResolutionStrategy.MYBATIS_MAPPER
                    : ResolutionStrategy.DATA_ACCESS_WITHOUT_EVIDENCE;
        }
        return ResolutionStrategy.JDT_CALL_HIERARCHY;
    }

    private boolean isDataAccess(ClassMetadata metadata) {
        return matchesAny(metadata.annotations(), DATA_ACCESS)
                || metadata.implementedTypes().stream().anyMatch(this::isDataAccessType)
                || metadata.extendedTypes().stream().anyMatch(this::isDataAccessType);
    }

    private boolean isDataAccessType(String typeName) {
        String rawType = typeName.contains("<") ? typeName.substring(0, typeName.indexOf('<')) : typeName;
        return DATA_ACCESS_TYPES.stream().anyMatch(candidate -> annotationName(rawType).equals(candidate));
    }

    private boolean isLombokGenerated(
            ClassMetadata metadata,
            String methodName,
            List<String> parameterTypes,
            String returnType,
            Optional<ClassMetadata> builderOwner) {
        if (matchesAny(metadata.annotations(), LOMBOK_GETTERS)
                && isGeneratedGetter(metadata, methodName, parameterTypes, returnType)) {
            return true;
        }
        if (matchesAny(metadata.annotations(), LOMBOK_SETTERS)
                && isGeneratedSetter(metadata, methodName, parameterTypes)) {
            return true;
        }
        if (matchesAny(metadata.annotations(), LOMBOK_OBJECT_METHODS)
                && isGeneratedObjectMethod(methodName, parameterTypes, returnType)) {
            return true;
        }
        if (matchesAny(metadata.annotations(), LOMBOK_BUILDERS)
                && "builder".equals(methodName)
                && parameterTypes.size() == 0
                && (simpleClassName(metadata.className()) + "Builder").equals(returnType)) {
            return true;
        }
        if ("build".equals(methodName)
                && parameterTypes.size() == 0
                && builderOwner.filter(owner -> isAssociatedBuilder(metadata, owner))
                        .filter(owner -> compatibleBuildReturn(returnType, owner))
                        .isPresent()) {
            return true;
        }
        return isGeneratedFluentAccessor(metadata, methodName, parameterTypes, returnType);
    }

    private boolean isGeneratedGetter(
            ClassMetadata metadata,
            String methodName,
            List<String> parameterTypes,
            String returnType) {
        if (parameterTypes.size() != 0) {
            return false;
        }
        Optional<FieldInfo> field = fieldForAccessor(metadata, methodName, "get");
        if (field.filter(candidate -> candidate.type().equals(returnType)).isPresent()) {
            return true;
        }
        return fieldForAccessor(metadata, methodName, "is")
                .filter(candidate -> "boolean".equals(candidate.type()))
                .filter(candidate -> "boolean".equals(returnType))
                .isPresent();
    }

    private boolean isGeneratedSetter(
            ClassMetadata metadata, String methodName, List<String> parameterTypes) {
        return parameterTypes.size() == 1
                && fieldForAccessor(metadata, methodName, "set")
                        .filter(field -> field.type().equals(parameterTypes.getFirst()))
                        .isPresent();
    }

    private boolean isGeneratedFluentAccessor(
            ClassMetadata metadata,
            String methodName,
            List<String> parameterTypes,
            String returnType) {
        if (!metadata.hasFluentAccessors()
                || !matchesAny(metadata.annotations(), LOMBOK_FLUENT_ACCESSORS)
                || metadata.fields().stream().noneMatch(field -> field.name().equals(methodName))) {
            return false;
        }
        FieldInfo field = metadata.fields().stream()
                .filter(candidate -> candidate.name().equals(methodName))
                .findFirst()
                .orElseThrow();
        boolean getter = parameterTypes.size() == 0
                && matchesAny(metadata.annotations(), LOMBOK_GETTERS)
                && field.type().equals(returnType);
        boolean setter = parameterTypes.size() == 1
                && matchesAny(metadata.annotations(), LOMBOK_SETTERS)
                && field.type().equals(parameterTypes.getFirst())
                && expectedFluentSetterReturn(metadata).equals(returnType);
        return getter || setter;
    }

    private boolean isGeneratedObjectMethod(
            String methodName, List<String> parameterTypes, String returnType) {
        if (!GENERATED_OBJECT_METHODS.contains(methodName)) {
            return false;
        }
        return switch (methodName) {
            case "toString" -> parameterTypes.size() == 0 && "String".equals(returnType);
            case "hashCode" -> parameterTypes.size() == 0 && "int".equals(returnType);
            case "equals" -> parameterTypes.equals(List.of("Object")) && "boolean".equals(returnType);
            default -> false;
        };
    }

    private boolean compatibleBuildReturn(String returnType, ClassMetadata owner) {
        return simpleClassName(owner.className()).equals(returnType);
    }

    private String expectedFluentSetterReturn(ClassMetadata metadata) {
        return metadata.hasChainedAccessors() ? simpleClassName(metadata.className()) : "void";
    }

    private String simpleClassName(String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot >= 0 ? className.substring(lastDot + 1) : className;
    }

    private Optional<FieldInfo> fieldForAccessor(
            ClassMetadata metadata, String methodName, String prefix) {
        if (!hasCapitalizedSuffix(methodName, prefix)) {
            return Optional.empty();
        }
        String fieldName = Introspector.decapitalize(methodName.substring(prefix.length()));
        return metadata.fields().stream()
                .filter(field -> field.name().equals(fieldName))
                .findFirst();
    }

    private boolean isAssociatedBuilder(ClassMetadata metadata, ClassMetadata owner) {
        String ownerSimpleName = simpleClassName(owner.className());
        return metadata.packageName().equals(owner.packageName())
                && metadata.className().equals(owner.className() + "." + ownerSimpleName + "Builder")
                && matchesAny(owner.annotations(), LOMBOK_BUILDERS);
    }

    private boolean hasCapitalizedSuffix(String methodName, String prefix) {
        return methodName.startsWith(prefix)
                && methodName.length() > prefix.length()
                && Character.isUpperCase(methodName.charAt(prefix.length()));
    }

    private boolean matchesAny(List<String> annotations, Set<String> expected) {
        return annotations.stream().map(this::annotationName).anyMatch(expected::contains);
    }

    private String annotationName(String writtenName) {
        int lastDot = writtenName.lastIndexOf('.');
        return lastDot >= 0 ? writtenName.substring(lastDot + 1) : writtenName;
    }
}
