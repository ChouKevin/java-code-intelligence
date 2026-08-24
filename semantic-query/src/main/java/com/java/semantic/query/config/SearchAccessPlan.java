package com.java.semantic.query.config;

import com.java.semantic.model.codefact.JavaIdentityNormalizer;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.index.SourceIndexScope;
import com.mongodb.client.model.Filters;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable Mongo predicate plan compiled from the central read-policy rules. */
public final class SearchAccessPlan {
    private final RepositoryId repositoryId;
    private final List<ReadPolicyProperties.PackageRule> packageRules;
    private final List<ReadPolicyProperties.ClassRule> classRules;
    private final List<ReadPolicyProperties.MethodRule> methodRules;

    SearchAccessPlan(RepositoryId repositoryId, ReadPolicyProperties properties) {
        this.repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        ReadPolicyProperties requiredProperties = Objects.requireNonNull(properties, "read policy properties are required");
        packageRules = requiredProperties.forbiddenPackages().stream().filter(rule -> rule.repoId().equals(repositoryId.value())).toList();
        classRules = requiredProperties.forbiddenClasses().stream().filter(rule -> rule.repoId().equals(repositoryId.value())).toList();
        methodRules = requiredProperties.forbiddenMethods().stream().filter(rule -> rule.repoId().equals(repositoryId.value())).toList();
    }

    public boolean isPackageVisible(String packagePrefix) {
        return packageRules.stream().noneMatch(rule -> packagePrefix.equals(rule.packagePrefix())
                || packagePrefix.startsWith(rule.packagePrefix() + ".") || rule.packagePrefix().startsWith(packagePrefix + "."));
    }

    public Bson authorized(Bson base) {
        List<Bson> requiredScope = List.of(Filters.exists("scopePackage"), Filters.exists("scopeClass"),
                Filters.exists("scopeMethod"), Filters.exists("scopeParameters"), Filters.exists("scopePath"));
        List<Bson> forbidden = new ArrayList<>();
        for (ReadPolicyProperties.PackageRule rule : packageRules) {
            forbidden.add(Filters.regex("scopePackage", "^" + Pattern.quote(rule.packagePrefix()) + "(?:\\.|$)"));
        }
        for (ReadPolicyProperties.ClassRule rule : classRules) {
            forbidden.add(Filters.and(Filters.eq("scopePackage", rule.packageName()), Filters.eq("scopeClass",
                    JavaIdentityNormalizer.className(rule.packageName(), rule.className()))));
        }
        for (ReadPolicyProperties.MethodRule rule : methodRules) {
            forbidden.add(Filters.and(Filters.eq("scopePackage", rule.packageName()), Filters.eq("scopeClass",
                    JavaIdentityNormalizer.className(rule.packageName(), rule.className())), Filters.eq("scopeMethod", rule.methodName()),
                    Filters.eq("scopeParameters", JavaIdentityNormalizer.parameterTypes(rule.parameterTypes()))));
        }
        Bson scoped = Filters.and(base, Filters.and(requiredScope));
        return forbidden.isEmpty() ? scoped : Filters.and(scoped, Filters.nor(forbidden));
    }

    /** Compiles source-row authorization before a generation-files Mongo reader can observe a row. */
    public Bson authorizedSource(Bson base) {
        List<Bson> required = List.of(Filters.exists("extractionIssueCode"), Filters.exists("scopeUsable"),
                Filters.exists("scopePackages"), Filters.exists("scopeClassKeys"), Filters.exists("scopeMethodKeys"));
        Bson scoped = Filters.and(base, Filters.and(required));
        if (packageRules.isEmpty() && classRules.isEmpty() && methodRules.isEmpty()) {
            return scoped;
        }
        List<Bson> forbidden = new ArrayList<>();
        for (ReadPolicyProperties.PackageRule rule : packageRules) {
            forbidden.add(Filters.regex("scopePackages", "^" + Pattern.quote(rule.packagePrefix()) + "(?:\\.|$)"));
        }
        for (ReadPolicyProperties.ClassRule rule : classRules) {
            String className = JavaIdentityNormalizer.className(rule.packageName(), rule.className());
            forbidden.add(Filters.eq("scopeClassKeys", SourceIndexScope.classKey(rule.packageName(), className)));
        }
        for (ReadPolicyProperties.MethodRule rule : methodRules) {
            String className = JavaIdentityNormalizer.className(rule.packageName(), rule.className());
            forbidden.add(Filters.eq("scopeMethodKeys", SourceIndexScope.methodKey(rule.packageName(), className,
                    rule.methodName(), JavaIdentityNormalizer.parameterTypes(rule.parameterTypes()))));
        }
        return Filters.and(scoped, Filters.eq("scopeUsable", true), Filters.nor(forbidden));
    }

    public RepositoryId repositoryId() { return repositoryId; }
}
