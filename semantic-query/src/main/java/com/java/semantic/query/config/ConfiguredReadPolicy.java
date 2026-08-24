package com.java.semantic.query.config;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.JavaIdentityNormalizer;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MapperStatementIdentity;
import com.java.semantic.model.codefact.MemberIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceTypeIdentity;

import java.util.Objects;

/** Query-side authorization policy. Repository visibility is checked before any pointer read. */
public final class ConfiguredReadPolicy {

    private final ReadPolicyProperties properties;

    public ConfiguredReadPolicy(ReadPolicyProperties properties) {
        this.properties = Objects.requireNonNull(properties, "read policy properties are required");
    }

    public boolean isRepositoryVisible(RepositoryId repositoryId) {
        Objects.requireNonNull(repositoryId, "repository id is required");
        return !properties.forbiddenRepositories().contains(repositoryId.value());
    }

    public boolean isSourceVisible(RepositoryId repositoryId, SourceTypeIdentity sourceType) {
        Objects.requireNonNull(repositoryId, "repository id is required");
        SourceTypeIdentity identity = Objects.requireNonNull(sourceType, "source type identity is required");
        return isJavaTypeVisible(repositoryId, identity.javaType());
    }

    public boolean isJavaTypeVisible(RepositoryId repositoryId, JavaTypeIdentity javaType) {
        Objects.requireNonNull(repositoryId, "repository id is required");
        JavaTypeIdentity identity = Objects.requireNonNull(javaType, "java type identity is required");
        return !forbiddenPackage(repositoryId, identity) && !forbiddenClass(repositoryId, identity);
    }

    public boolean isCodeFactVisible(RepositoryId repositoryId, CodeFactIdentity codeFact) {
        Objects.requireNonNull(repositoryId, "repository id is required");
        CodeFactIdentity identity = Objects.requireNonNull(codeFact, "code fact identity is required");
        if (identity.canonicalIdentity() instanceof MethodTarget method) {
            return isSourceVisible(repositoryId, method.sourceType()) && !forbiddenMethod(repositoryId, method);
        }
        if (identity.canonicalIdentity() instanceof SourceTypeIdentity sourceType) {
            return isSourceVisible(repositoryId, sourceType);
        }
        if (identity.canonicalIdentity() instanceof MemberIdentity member) {
            return isSourceVisible(repositoryId, member.owner());
        }
        if (identity.canonicalIdentity() instanceof MapperStatementIdentity mapperStatement) {
            return isMapperVisible(repositoryId, mapperStatement);
        }
        if (identity.canonicalIdentity() instanceof JavaTypeIdentity javaType) {
            return isJavaTypeVisible(repositoryId, javaType);
        }
        return false;
    }

    private boolean forbiddenPackage(RepositoryId repositoryId, JavaTypeIdentity identity) {
        return properties.forbiddenPackages().stream().anyMatch(rule -> rule.repoId().equals(repositoryId.value())
                && (identity.packageName().equals(rule.packagePrefix())
                || identity.packageName().startsWith(rule.packagePrefix() + ".")));
    }

    private boolean forbiddenClass(RepositoryId repositoryId, JavaTypeIdentity identity) {
        return properties.forbiddenClasses().stream().anyMatch(rule -> rule.repoId().equals(repositoryId.value())
                && rule.packageName().equals(identity.packageName())
                && JavaIdentityNormalizer.className(rule.packageName(), rule.className()).equals(identity.className()));
    }

    private boolean forbiddenMethod(RepositoryId repositoryId, MethodTarget method) {
        return properties.forbiddenMethods().stream().anyMatch(rule -> rule.repoId().equals(repositoryId.value())
                && rule.packageName().equals(method.packageName())
                && JavaIdentityNormalizer.className(rule.packageName(), rule.className())
                .equals(JavaIdentityNormalizer.className(method.packageName(), method.className()))
                && rule.methodName().equals(method.methodName())
                && JavaIdentityNormalizer.parameterTypes(rule.parameterTypes())
                .equals(JavaIdentityNormalizer.parameterTypes(method.parameterTypes())));
    }

    private boolean isMapperVisible(RepositoryId repositoryId, MapperStatementIdentity mapperStatement) {
        String namespace = JavaIdentityNormalizer.className("", mapperStatement.namespace());
        boolean forbiddenNamespace = properties.forbiddenPackages().stream().anyMatch(rule -> rule.repoId().equals(repositoryId.value())
                && namespaceInPackage(namespace, rule.packagePrefix()))
                || properties.forbiddenClasses().stream().anyMatch(rule -> rule.repoId().equals(repositoryId.value())
                && namespace.equals(qualifiedClassName(rule.packageName(), rule.className())));
        boolean forbiddenSameNameMethod = properties.forbiddenMethods().stream().anyMatch(rule -> rule.repoId().equals(repositoryId.value())
                && namespace.equals(qualifiedClassName(rule.packageName(), rule.className()))
                && rule.methodName().equals(mapperStatement.statementId()));
        return !forbiddenNamespace && !forbiddenSameNameMethod;
    }

    private static boolean namespaceInPackage(String namespace, String packagePrefix) {
        return namespace.equals(packagePrefix) || namespace.startsWith(packagePrefix + ".");
    }

    private static String qualifiedClassName(String packageName, String className) {
        String normalizedClassName = JavaIdentityNormalizer.className(packageName, className);
        return packageName.isBlank() ? normalizedClassName : packageName + "." + normalizedClassName;
    }
}
