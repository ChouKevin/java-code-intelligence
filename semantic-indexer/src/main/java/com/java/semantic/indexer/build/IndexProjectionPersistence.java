package com.java.semantic.indexer.build;

import com.java.semantic.model.codefact.CanonicalIdentity;
import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.DeclaredType;
import com.java.semantic.model.codefact.EntryPointIdentity;
import com.java.semantic.model.codefact.EntryPointKind;
import com.java.semantic.model.codefact.EntryPointTrigger;
import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MapperStatementIdentity;
import com.java.semantic.model.codefact.MemberIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.RelationIdentity;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.RelationTarget;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.index.EntryPointDocument;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.ProjectionName;
import com.java.semantic.model.index.SearchDocument;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Optional-free persistence values reconstructed only through the standard Spring Data converter. */
final class IndexProjectionPersistence {

    private IndexProjectionPersistence() { }

    record EntryPointPersistence(StoredEntryPoint entryPoint) {

        EntryPointPersistence {
            entryPoint = Objects.requireNonNull(entryPoint, "stored entry point is required");
        }

        static EntryPointPersistence from(EntryPointDocument document) {
            return new EntryPointPersistence(StoredEntryPoint.from(document));
        }

        EntryPointDocument toModel() {
            return entryPoint.toModel();
        }
    }

    record SearchPersistence(StoredSearch search) {

        SearchPersistence {
            search = Objects.requireNonNull(search, "stored search document is required");
        }

        static SearchPersistence from(SearchDocument document) {
            return new SearchPersistence(StoredSearch.from(document));
        }

        SearchDocument toModel() {
            return search.toModel();
        }
    }

    record StoredEntryPoint(String repositoryId, String generationId, StoredCodeFact fact, String kind,
                            StoredMethodTarget method, StoredEntryPointTrigger trigger, StoredSourceRange range) {

        StoredEntryPoint {
            repositoryId = Objects.requireNonNull(repositoryId, "stored repository id is required");
            generationId = Objects.requireNonNull(generationId, "stored generation id is required");
            fact = Objects.requireNonNull(fact, "stored fact is required");
            kind = Objects.requireNonNull(kind, "stored entry point kind is required");
            method = Objects.requireNonNull(method, "stored method is required");
            trigger = Objects.requireNonNull(trigger, "stored trigger is required");
            range = Objects.requireNonNull(range, "stored source range is required");
        }

        static StoredEntryPoint from(EntryPointDocument document) {
            EntryPointDocument requiredDocument = Objects.requireNonNull(document, "entry point document is required");
            return new StoredEntryPoint(requiredDocument.repositoryId().value(), requiredDocument.generationId().value(),
                    StoredCodeFact.from(requiredDocument.fact()), requiredDocument.kind().name(), StoredMethodTarget.from(requiredDocument.method()),
                    StoredEntryPointTrigger.from(requiredDocument.trigger()), StoredSourceRange.from(requiredDocument.range()));
        }

        EntryPointDocument toModel() {
            return new EntryPointDocument(new RepositoryId(repositoryId), new GenerationId(generationId), fact.toModel(),
                    EntryPointKind.valueOf(kind), method.toModel(), trigger.toModel(), range.toModel());
        }
    }

    record StoredSearch(String repositoryId, String generationId, String factId, String kind, List<String> normalizedTokens,
                        boolean hasPackageName, String packageName, String authoritativeProjection,
                        StoredCodeFactIdentity authoritativeIdentity) {

        StoredSearch {
            repositoryId = Objects.requireNonNull(repositoryId, "stored repository id is required");
            generationId = Objects.requireNonNull(generationId, "stored generation id is required");
            factId = Objects.requireNonNull(factId, "stored fact id is required");
            kind = Objects.requireNonNull(kind, "stored search kind is required");
            normalizedTokens = List.copyOf(Objects.requireNonNull(normalizedTokens, "stored normalized tokens are required"));
            packageName = Objects.requireNonNull(packageName, "stored package name is required");
            authoritativeProjection = Objects.requireNonNull(authoritativeProjection, "stored authoritative projection is required");
            authoritativeIdentity = Objects.requireNonNull(authoritativeIdentity, "stored authoritative identity is required");
        }

        static StoredSearch from(SearchDocument document) {
            SearchDocument requiredDocument = Objects.requireNonNull(document, "search document is required");
            return new StoredSearch(requiredDocument.repositoryId().value(), requiredDocument.generationId().value(), requiredDocument.factId().value(),
                    requiredDocument.kind().name(), requiredDocument.normalizedTokens(), requiredDocument.packageName().isPresent(),
                    requiredDocument.packageName().orElse(""), requiredDocument.authoritativeProjection().name(),
                    StoredCodeFactIdentity.from(requiredDocument.authoritativeIdentity()));
        }

        SearchDocument toModel() {
            Optional<String> packageValue = hasPackageName ? Optional.of(packageName) : Optional.empty();
            return new SearchDocument(new RepositoryId(repositoryId), new GenerationId(generationId), new CodeFactId(factId),
                    CodeFactKind.valueOf(kind), normalizedTokens, packageValue, ProjectionName.valueOf(authoritativeProjection),
                    authoritativeIdentity.toModel());
        }
    }

    record StoredCodeFact(String id, StoredCodeFactIdentity identity) {

        StoredCodeFact {
            id = Objects.requireNonNull(id, "stored fact id is required");
            identity = Objects.requireNonNull(identity, "stored fact identity is required");
        }

        static StoredCodeFact from(CodeFact fact) {
            CodeFact requiredFact = Objects.requireNonNull(fact, "code fact is required");
            return new StoredCodeFact(requiredFact.id().value(), StoredCodeFactIdentity.from(requiredFact.identity()));
        }

        CodeFact toModel() {
            return new CodeFact(new CodeFactId(id), identity.toModel());
        }
    }

    record StoredCodeFactIdentity(String repositoryId, String revision, String kind, StoredCanonicalIdentity canonicalIdentity) {

        StoredCodeFactIdentity {
            repositoryId = Objects.requireNonNull(repositoryId, "stored repository id is required");
            revision = Objects.requireNonNull(revision, "stored repository revision is required");
            kind = Objects.requireNonNull(kind, "stored code fact kind is required");
            canonicalIdentity = Objects.requireNonNull(canonicalIdentity, "stored canonical identity is required");
        }

        static StoredCodeFactIdentity from(CodeFactIdentity identity) {
            CodeFactIdentity requiredIdentity = Objects.requireNonNull(identity, "code fact identity is required");
            return new StoredCodeFactIdentity(requiredIdentity.repositoryId().value(), requiredIdentity.repositoryRevision().value(),
                    requiredIdentity.kind().name(), StoredCanonicalIdentity.from(requiredIdentity.canonicalIdentity()));
        }

        CodeFactIdentity toModel() {
            return new CodeFactIdentity(new RepositoryId(repositoryId), new RepositoryRevision(revision), CodeFactKind.valueOf(kind),
                    canonicalIdentity.toModel());
        }
    }

    sealed interface StoredCanonicalIdentity permits StoredJavaTypeIdentity, StoredSourceTypeIdentity, StoredMethodTarget,
            StoredMemberIdentity, StoredMapperStatementIdentity, StoredEntryPointIdentity, StoredRelationIdentity {

        CanonicalIdentity toModel();

        static StoredCanonicalIdentity from(CanonicalIdentity identity) {
            CanonicalIdentity requiredIdentity = Objects.requireNonNull(identity, "canonical identity is required");
            if (requiredIdentity instanceof JavaTypeIdentity javaType) {
                return StoredJavaTypeIdentity.from(javaType);
            }
            if (requiredIdentity instanceof SourceTypeIdentity sourceType) {
                return StoredSourceTypeIdentity.from(sourceType);
            }
            if (requiredIdentity instanceof MethodTarget method) {
                return StoredMethodTarget.from(method);
            }
            if (requiredIdentity instanceof MemberIdentity member) {
                return StoredMemberIdentity.from(member);
            }
            if (requiredIdentity instanceof MapperStatementIdentity mapperStatement) {
                return StoredMapperStatementIdentity.from(mapperStatement);
            }
            if (requiredIdentity instanceof EntryPointIdentity entryPoint) {
                return StoredEntryPointIdentity.from(entryPoint);
            }
            if (requiredIdentity instanceof RelationIdentity relation) {
                return StoredRelationIdentity.from(relation);
            }
            throw new IllegalArgumentException("unsupported canonical identity: " + requiredIdentity.getClass().getName());
        }
    }

    record StoredJavaTypeIdentity(String packageName, String className) implements StoredCanonicalIdentity {

        StoredJavaTypeIdentity {
            packageName = Objects.requireNonNull(packageName, "stored package name is required");
            className = Objects.requireNonNull(className, "stored class name is required");
        }

        static StoredJavaTypeIdentity from(JavaTypeIdentity identity) {
            return new StoredJavaTypeIdentity(identity.packageName(), identity.className());
        }

        @Override
        public JavaTypeIdentity toModel() {
            return new JavaTypeIdentity(packageName, className);
        }
    }

    record StoredSourceTypeIdentity(StoredJavaTypeIdentity javaType, String sourceFile) implements StoredCanonicalIdentity {

        StoredSourceTypeIdentity {
            javaType = Objects.requireNonNull(javaType, "stored Java type is required");
            sourceFile = Objects.requireNonNull(sourceFile, "stored source file is required");
        }

        static StoredSourceTypeIdentity from(SourceTypeIdentity identity) {
            return new StoredSourceTypeIdentity(StoredJavaTypeIdentity.from(identity.javaType()), identity.sourceFile());
        }

        @Override
        public SourceTypeIdentity toModel() {
            return new SourceTypeIdentity(javaType.toModel(), sourceFile);
        }
    }

    record StoredMethodTarget(StoredSourceTypeIdentity sourceType, String methodName, List<String> parameterTypes)
            implements StoredCanonicalIdentity {

        StoredMethodTarget {
            sourceType = Objects.requireNonNull(sourceType, "stored source type is required");
            methodName = Objects.requireNonNull(methodName, "stored method name is required");
            parameterTypes = List.copyOf(Objects.requireNonNull(parameterTypes, "stored parameter types are required"));
        }

        static StoredMethodTarget from(MethodTarget target) {
            return new StoredMethodTarget(StoredSourceTypeIdentity.from(target.sourceType()), target.methodName(), target.parameterTypes());
        }

        @Override
        public MethodTarget toModel() {
            return new MethodTarget(sourceType.toModel(), methodName, parameterTypes);
        }
    }

    record StoredMemberIdentity(StoredSourceTypeIdentity owner, String name) implements StoredCanonicalIdentity {

        StoredMemberIdentity {
            owner = Objects.requireNonNull(owner, "stored member owner is required");
            name = Objects.requireNonNull(name, "stored member name is required");
        }

        static StoredMemberIdentity from(MemberIdentity identity) {
            return new StoredMemberIdentity(StoredSourceTypeIdentity.from(identity.owner()), identity.name());
        }

        @Override
        public MemberIdentity toModel() {
            return new MemberIdentity(owner.toModel(), name);
        }
    }

    record StoredMapperStatementIdentity(String namespace, String statementId, String resourcePath) implements StoredCanonicalIdentity {

        StoredMapperStatementIdentity {
            namespace = Objects.requireNonNull(namespace, "stored mapper namespace is required");
            statementId = Objects.requireNonNull(statementId, "stored mapper statement id is required");
            resourcePath = Objects.requireNonNull(resourcePath, "stored mapper resource path is required");
        }

        static StoredMapperStatementIdentity from(MapperStatementIdentity identity) {
            return new StoredMapperStatementIdentity(identity.namespace(), identity.statementId(), identity.resourcePath());
        }

        @Override
        public MapperStatementIdentity toModel() {
            return new MapperStatementIdentity(namespace, statementId, resourcePath);
        }
    }

    record StoredEntryPointIdentity(String entryPointKind, StoredMethodTarget method, StoredEntryPointTrigger trigger)
            implements StoredCanonicalIdentity {

        StoredEntryPointIdentity {
            entryPointKind = Objects.requireNonNull(entryPointKind, "stored entry point kind is required");
            method = Objects.requireNonNull(method, "stored entry point method is required");
            trigger = Objects.requireNonNull(trigger, "stored entry point trigger is required");
        }

        static StoredEntryPointIdentity from(EntryPointIdentity identity) {
            return new StoredEntryPointIdentity(identity.entryPointKind().name(), StoredMethodTarget.from(identity.method()),
                    StoredEntryPointTrigger.from(identity.trigger()));
        }

        @Override
        public EntryPointIdentity toModel() {
            return new EntryPointIdentity(EntryPointKind.valueOf(entryPointKind), method.toModel(), trigger.toModel());
        }
    }

    record StoredEntryPointTrigger(boolean hasHttpMethod, String httpMethod, boolean hasHttpPath, String httpPath,
                                   boolean hasDestination, String destinationBroker, String destination,
                                   boolean hasSchedule, String schedule) {

        StoredEntryPointTrigger {
            httpMethod = Objects.requireNonNull(httpMethod, "stored HTTP method is required");
            httpPath = Objects.requireNonNull(httpPath, "stored HTTP path is required");
            destinationBroker = Objects.requireNonNull(destinationBroker, "stored destination broker is required");
            destination = Objects.requireNonNull(destination, "stored destination is required");
            schedule = Objects.requireNonNull(schedule, "stored schedule is required");
        }

        static StoredEntryPointTrigger from(EntryPointTrigger trigger) {
            EntryPointTrigger requiredTrigger = Objects.requireNonNull(trigger, "entry point trigger is required");
            Optional<ExternalTarget.Destination> destination = requiredTrigger.destination();
            return new StoredEntryPointTrigger(requiredTrigger.httpMethod().isPresent(), requiredTrigger.httpMethod().orElse(""),
                    requiredTrigger.httpPath().isPresent(), requiredTrigger.httpPath().orElse(""), destination.isPresent(),
                    destination.map(ExternalTarget.Destination::broker).orElse(""),
                    destination.map(ExternalTarget.Destination::destination).orElse(""), requiredTrigger.schedule().isPresent(),
                    requiredTrigger.schedule().orElse(""));
        }

        EntryPointTrigger toModel() {
            Optional<ExternalTarget.Destination> destinationValue = hasDestination
                    ? Optional.of(new ExternalTarget.Destination(destinationBroker, destination)) : Optional.empty();
            return new EntryPointTrigger(hasHttpMethod ? Optional.of(httpMethod) : Optional.empty(),
                    hasHttpPath ? Optional.of(httpPath) : Optional.empty(), destinationValue,
                    hasSchedule ? Optional.of(schedule) : Optional.empty());
        }
    }

    record StoredRelationIdentity(StoredCodeFactIdentity from, String relationKind, StoredRelationTarget target,
                                  StoredSourceRange occurrence) implements StoredCanonicalIdentity {

        StoredRelationIdentity {
            from = Objects.requireNonNull(from, "stored relation source is required");
            relationKind = Objects.requireNonNull(relationKind, "stored relation kind is required");
            target = Objects.requireNonNull(target, "stored relation target is required");
            occurrence = Objects.requireNonNull(occurrence, "stored relation occurrence is required");
        }

        static StoredRelationIdentity from(RelationIdentity identity) {
            return new StoredRelationIdentity(StoredCodeFactIdentity.from(identity.from()), identity.relationKind().name(),
                    StoredRelationTarget.from(identity.target()), StoredSourceRange.from(identity.occurrence()));
        }

        @Override
        public RelationIdentity toModel() {
            return new RelationIdentity(from.toModel(), RelationKind.valueOf(relationKind), target.toModel(), occurrence.toModel());
        }
    }

    sealed interface StoredRelationTarget permits StoredRelationTarget.Internal, StoredRelationTarget.External {

        RelationTarget toModel();

        static StoredRelationTarget from(RelationTarget target) {
            RelationTarget requiredTarget = Objects.requireNonNull(target, "relation target is required");
            if (requiredTarget instanceof RelationTarget.Internal internal) {
                return new Internal(StoredCodeFactIdentity.from(internal.identity()));
            }
            if (requiredTarget instanceof RelationTarget.External external) {
                return new External(StoredExternalTarget.from(external.target()));
            }
            throw new IllegalArgumentException("unsupported relation target: " + requiredTarget.getClass().getName());
        }

        record Internal(StoredCodeFactIdentity identity) implements StoredRelationTarget {

            public Internal {
                identity = Objects.requireNonNull(identity, "stored internal relation target is required");
            }

            @Override
            public RelationTarget.Internal toModel() {
                return new RelationTarget.Internal(identity.toModel());
            }
        }

        record External(StoredExternalTarget target) implements StoredRelationTarget {

            public External {
                target = Objects.requireNonNull(target, "stored external relation target is required");
            }

            @Override
            public RelationTarget.External toModel() {
                return new RelationTarget.External(target.toModel());
            }
        }
    }

    sealed interface StoredExternalTarget permits StoredExternalTarget.NominalType, StoredExternalTarget.Endpoint,
            StoredExternalTarget.Destination, StoredExternalTarget.ConfigurationKey, StoredExternalTarget.SqlIdentifier,
            StoredExternalTarget.UnresolvedCall {

        ExternalTarget toModel();

        static StoredExternalTarget from(ExternalTarget target) {
            ExternalTarget requiredTarget = Objects.requireNonNull(target, "external target is required");
            if (requiredTarget instanceof ExternalTarget.NominalType nominalType) {
                return new NominalType(nominalType.type().canonicalName());
            }
            if (requiredTarget instanceof ExternalTarget.Endpoint endpoint) {
                return new Endpoint(endpoint.method(), endpoint.uri());
            }
            if (requiredTarget instanceof ExternalTarget.Destination destination) {
                return new Destination(destination.broker(), destination.destination());
            }
            if (requiredTarget instanceof ExternalTarget.ConfigurationKey configurationKey) {
                return new ConfigurationKey(configurationKey.key());
            }
            if (requiredTarget instanceof ExternalTarget.SqlIdentifier sqlIdentifier) {
                return new SqlIdentifier(sqlIdentifier.identifier());
            }
            if (requiredTarget instanceof ExternalTarget.UnresolvedCall unresolvedCall) {
                return new UnresolvedCall(unresolvedCall.expression(), unresolvedCall.receiver(), unresolvedCall.methodName(), unresolvedCall.arity());
            }
            throw new IllegalArgumentException("unsupported external target: " + requiredTarget.getClass().getName());
        }

        record NominalType(String canonicalName) implements StoredExternalTarget {
            public NominalType { canonicalName = Objects.requireNonNull(canonicalName, "stored declared type is required"); }
            @Override public ExternalTarget.NominalType toModel() { return new ExternalTarget.NominalType(new DeclaredType(canonicalName)); }
        }

        record Endpoint(String method, String uri) implements StoredExternalTarget {
            public Endpoint { method = Objects.requireNonNull(method, "stored endpoint method is required"); uri = Objects.requireNonNull(uri, "stored endpoint URI is required"); }
            @Override public ExternalTarget.Endpoint toModel() { return new ExternalTarget.Endpoint(method, uri); }
        }

        record Destination(String broker, String destination) implements StoredExternalTarget {
            public Destination { broker = Objects.requireNonNull(broker, "stored broker is required"); destination = Objects.requireNonNull(destination, "stored destination is required"); }
            @Override public ExternalTarget.Destination toModel() { return new ExternalTarget.Destination(broker, destination); }
        }

        record ConfigurationKey(String key) implements StoredExternalTarget {
            public ConfigurationKey { key = Objects.requireNonNull(key, "stored configuration key is required"); }
            @Override public ExternalTarget.ConfigurationKey toModel() { return new ExternalTarget.ConfigurationKey(key); }
        }

        record SqlIdentifier(String identifier) implements StoredExternalTarget {
            public SqlIdentifier { identifier = Objects.requireNonNull(identifier, "stored SQL identifier is required"); }
            @Override public ExternalTarget.SqlIdentifier toModel() { return new ExternalTarget.SqlIdentifier(identifier); }
        }

        record UnresolvedCall(String expression, String receiver, String methodName, int arity) implements StoredExternalTarget {
            public UnresolvedCall { expression = Objects.requireNonNull(expression, "stored call expression is required"); receiver = Objects.requireNonNull(receiver, "stored call receiver is required"); methodName = Objects.requireNonNull(methodName, "stored call method is required"); }
            @Override public ExternalTarget.UnresolvedCall toModel() { return new ExternalTarget.UnresolvedCall(expression, receiver, methodName, arity); }
        }
    }

    record StoredSourceRange(String sourceFile, int startLine, int startCharacter, int endLine, int endCharacter) {

        StoredSourceRange {
            sourceFile = Objects.requireNonNull(sourceFile, "stored source file is required");
        }

        static StoredSourceRange from(SourceRange range) {
            SourceRange requiredRange = Objects.requireNonNull(range, "source range is required");
            return new StoredSourceRange(requiredRange.sourceFile(), requiredRange.range().start().line(), requiredRange.range().start().character(),
                    requiredRange.range().end().line(), requiredRange.range().end().character());
        }

        SourceRange toModel() {
            return new SourceRange(sourceFile, new SyntaxRange(new SyntaxPosition(startLine, startCharacter), new SyntaxPosition(endLine, endCharacter)));
        }
    }
}
