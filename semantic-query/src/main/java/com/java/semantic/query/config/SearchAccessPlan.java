package com.java.semantic.query.config;

import com.java.semantic.model.codefact.JavaIdentityNormalizer;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.index.SourceIndexScope;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable Mongo predicate plan compiled from the central read-policy rules. */
public final class SearchAccessPlan {
    private static final String METHOD_PACKAGE = "fact.identity.canonicalIdentity.sourceType.javaType.packageName";
    private static final String METHOD_CLASS = "fact.identity.canonicalIdentity.sourceType.javaType.className";
    private static final String METHOD_NAME = "fact.identity.canonicalIdentity.methodName";
    private static final String METHOD_PARAMETERS = "fact.identity.canonicalIdentity.parameterTypes";
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

    /** Compiles method authorization from the canonical stored identity, never its flattened query scope. */
    public Bson authorizedMethod(Bson base) {
        List<Bson> requiredIdentity = List.of(Filters.exists(METHOD_PACKAGE), Filters.exists(METHOD_CLASS),
                Filters.exists(METHOD_NAME), Filters.exists(METHOD_PARAMETERS));
        List<Bson> forbidden = new ArrayList<>();
        for (ReadPolicyProperties.PackageRule rule : packageRules) {
            forbidden.add(Filters.regex(METHOD_PACKAGE, "^" + Pattern.quote(rule.packagePrefix()) + "(?:\\.|$)"));
        }
        for (ReadPolicyProperties.ClassRule rule : classRules) {
            forbidden.add(Filters.and(Filters.eq(METHOD_PACKAGE, rule.packageName()), Filters.eq(METHOD_CLASS,
                    JavaIdentityNormalizer.className(rule.packageName(), rule.className()))));
        }
        for (ReadPolicyProperties.MethodRule rule : methodRules) {
            forbidden.add(Filters.and(Filters.eq(METHOD_PACKAGE, rule.packageName()), Filters.eq(METHOD_CLASS,
                    JavaIdentityNormalizer.className(rule.packageName(), rule.className())), Filters.eq(METHOD_NAME, rule.methodName()),
                    normalizedMethodParameters(JavaIdentityNormalizer.parameterTypes(rule.parameterTypes()))));
        }
        Bson typed = Filters.and(base, Filters.and(requiredIdentity));
        return forbidden.isEmpty() ? typed : Filters.and(typed, Filters.nor(forbidden));
    }

    private static Bson normalizedMethodParameters(List<String> parameterTypes) {
        Document normalizedParameter = new Document("$let", new Document("vars", new Document("value", new Document("$replaceAll",
                new Document("input", new Document("$trim", new Document("input", "$$parameter")))
                        .append("find", "...").append("replacement", "[]"))))
                .append("in", new Document("$let", new Document("vars", new Document("match", new Document("$regexFind",
                        new Document("input", "$$value").append("regex", "(?:^|\\.)([^.<]+)(?:<.*>)?((?:\\[\\])*)$"))))
                        .append("in", new Document("$concat", List.of(new Document("$arrayElemAt", List.of("$$match.captures", 0)),
                                new Document("$arrayElemAt", List.of("$$match.captures", 1))))))));
        Document normalizedParameters = new Document("$map", new Document("input", "$" + METHOD_PARAMETERS)
                .append("as", "parameter").append("in", normalizedParameter));
        return Filters.expr(new Document("$eq", List.of(normalizedParameters, parameterTypes)));
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
