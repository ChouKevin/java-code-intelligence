package com.java.semantic.model.codefact;

import com.java.semantic.model.support.ModelValidation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable, queryable authority scope derived from a canonical code-fact identity. */
public record CodeFactScope(String packageName, String className, Optional<String> methodName,
                            List<String> parameterTypes, Optional<String> sourcePath) {

    public CodeFactScope {
        packageName = Objects.requireNonNull(packageName, "package name is required");
        className = ModelValidation.requiredText(className, "class name");
        methodName = Objects.requireNonNull(methodName, "method name is required")
                .map(value -> ModelValidation.requiredText(value, "method name"));
        parameterTypes = List.copyOf(Objects.requireNonNull(parameterTypes, "parameter types are required"));
        sourcePath = Objects.requireNonNull(sourcePath, "source path is required")
                .map(value -> ModelValidation.repositoryRelativePath(value));
        ModelValidation.require(methodName.isPresent() || parameterTypes.isEmpty(),
                "parameter types require a method name");
    }

    public static CodeFactScope from(CodeFactIdentity identity) {
        CodeFactIdentity requiredIdentity = Objects.requireNonNull(identity, "code fact identity is required");
        return from(requiredIdentity.canonicalIdentity());
    }

    private static CodeFactScope from(CanonicalIdentity identity) {
        if (identity instanceof MethodTarget method) {
            return methodScope(method);
        }
        if (identity instanceof SourceTypeIdentity sourceType) {
            return typeScope(sourceType);
        }
        if (identity instanceof MemberIdentity member) {
            return typeScope(member.owner());
        }
        if (identity instanceof MapperStatementIdentity mapper) {
            int separator = mapper.namespace().lastIndexOf('.');
            String packageName = separator < 0 ? "" : mapper.namespace().substring(0, separator);
            String className = separator < 0 ? mapper.namespace() : mapper.namespace().substring(separator + 1);
            return new CodeFactScope(packageName, className, Optional.of(mapper.statementId()), List.of(),
                    Optional.of(mapper.resourcePath()));
        }
        if (identity instanceof EntryPointIdentity entryPoint) {
            return methodScope(entryPoint.method());
        }
        if (identity instanceof RelationIdentity relation) {
            return from(relation.from().canonicalIdentity());
        }
        if (identity instanceof JavaTypeIdentity javaType) {
            return new CodeFactScope(javaType.packageName(), javaType.className(), Optional.empty(), List.of(), Optional.empty());
        }
        throw new IllegalArgumentException("unsupported code fact scope identity");
    }

    private static CodeFactScope methodScope(MethodTarget method) {
        return new CodeFactScope(method.packageName(), method.className(), Optional.of(method.methodName()),
                JavaIdentityNormalizer.parameterTypes(method.parameterTypes()), Optional.of(method.sourceFile()));
    }

    private static CodeFactScope typeScope(SourceTypeIdentity type) {
        return new CodeFactScope(type.javaType().packageName(), type.javaType().className(), Optional.empty(), List.of(),
                Optional.of(type.sourceFile()));
    }
}
