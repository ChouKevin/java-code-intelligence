package com.java.semantic.model.index.persistence;

import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.EntryPointIdentity;
import com.java.semantic.model.codefact.EntryPointKind;
import com.java.semantic.model.codefact.EntryPointTrigger;
import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.index.EntryPointDocument;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Converter-safe, optional-free persisted shape for one ENTRY_POINTS row. */
public record EntryPointPersistence(StoredEntryPoint entryPoint) {
    public EntryPointPersistence {
        entryPoint = Objects.requireNonNull(entryPoint, "stored entry point is required");
    }

    public static EntryPointPersistence from(EntryPointDocument document) {
        return new EntryPointPersistence(StoredEntryPoint.from(document));
    }

    public EntryPointDocument toModel() {
        return entryPoint.toModel();
    }

    public record StoredEntryPoint(String repositoryId, String generationId, StoredCodeFact fact, String kind,
                                   StoredMethodTarget method, StoredEntryPointTrigger trigger, StoredSourceRange range) {
        public StoredEntryPoint {
            repositoryId = Objects.requireNonNull(repositoryId, "stored repository id is required");
            generationId = Objects.requireNonNull(generationId, "stored generation id is required");
            fact = Objects.requireNonNull(fact, "stored fact is required");
            kind = Objects.requireNonNull(kind, "stored entry point kind is required");
            method = Objects.requireNonNull(method, "stored method is required");
            trigger = Objects.requireNonNull(trigger, "stored trigger is required");
            range = Objects.requireNonNull(range, "stored source range is required");
        }

        static StoredEntryPoint from(EntryPointDocument document) {
            EntryPointDocument required = Objects.requireNonNull(document, "entry point document is required");
            return new StoredEntryPoint(required.repositoryId().value(), required.generationId().value(), StoredCodeFact.from(required.fact()),
                    required.kind().name(), StoredMethodTarget.from(required.method()), StoredEntryPointTrigger.from(required.trigger()),
                    StoredSourceRange.from(required.range()));
        }

        EntryPointDocument toModel() {
            return new EntryPointDocument(new RepositoryId(repositoryId), new GenerationId(generationId), fact.toModel(),
                    EntryPointKind.valueOf(kind), method.toModel(), trigger.toModel(), range.toModel());
        }
    }

    public record StoredCodeFact(String id, StoredCodeFactIdentity identity) {
        public StoredCodeFact {
            id = Objects.requireNonNull(id, "stored fact id is required");
            identity = Objects.requireNonNull(identity, "stored fact identity is required");
        }
        static StoredCodeFact from(CodeFact fact) {
            CodeFact required = Objects.requireNonNull(fact, "code fact is required");
            return new StoredCodeFact(required.id().value(), StoredCodeFactIdentity.from(required.identity()));
        }
        CodeFact toModel() { return new CodeFact(new CodeFactId(id), identity.toModel()); }
    }

    public record StoredCodeFactIdentity(String repositoryId, String revision, String kind, StoredEntryPointIdentity canonicalIdentity) {
        public StoredCodeFactIdentity {
            repositoryId = Objects.requireNonNull(repositoryId, "stored repository id is required");
            revision = Objects.requireNonNull(revision, "stored revision is required");
            kind = Objects.requireNonNull(kind, "stored kind is required");
            canonicalIdentity = Objects.requireNonNull(canonicalIdentity, "stored identity is required");
        }
        static StoredCodeFactIdentity from(CodeFactIdentity identity) {
            CodeFactIdentity required = Objects.requireNonNull(identity, "code fact identity is required");
            if (!(required.canonicalIdentity() instanceof EntryPointIdentity entryPoint)) {
                throw new IllegalArgumentException("entry point fact requires entry point identity");
            }
            return new StoredCodeFactIdentity(required.repositoryId().value(), required.repositoryRevision().value(), required.kind().name(),
                    StoredEntryPointIdentity.from(entryPoint));
        }
        CodeFactIdentity toModel() {
            return new CodeFactIdentity(new RepositoryId(repositoryId), new RepositoryRevision(revision), CodeFactKind.valueOf(kind),
                    canonicalIdentity.toModel());
        }
    }

    public record StoredEntryPointIdentity(String entryPointKind, StoredMethodTarget method, StoredEntryPointTrigger trigger) {
        public StoredEntryPointIdentity {
            entryPointKind = Objects.requireNonNull(entryPointKind, "stored entry point kind is required");
            method = Objects.requireNonNull(method, "stored method is required");
            trigger = Objects.requireNonNull(trigger, "stored trigger is required");
        }
        static StoredEntryPointIdentity from(EntryPointIdentity identity) {
            return new StoredEntryPointIdentity(identity.entryPointKind().name(), StoredMethodTarget.from(identity.method()),
                    StoredEntryPointTrigger.from(identity.trigger()));
        }
        EntryPointIdentity toModel() { return new EntryPointIdentity(EntryPointKind.valueOf(entryPointKind), method.toModel(), trigger.toModel()); }
    }

    public record StoredMethodTarget(StoredSourceTypeIdentity sourceType, String methodName, List<String> parameterTypes) {
        public StoredMethodTarget {
            sourceType = Objects.requireNonNull(sourceType, "stored source type is required");
            methodName = Objects.requireNonNull(methodName, "stored method name is required");
            parameterTypes = List.copyOf(Objects.requireNonNull(parameterTypes, "stored parameter types are required"));
        }
        static StoredMethodTarget from(MethodTarget target) {
            return new StoredMethodTarget(StoredSourceTypeIdentity.from(target.sourceType()), target.methodName(), target.parameterTypes());
        }
        MethodTarget toModel() { return new MethodTarget(sourceType.toModel(), methodName, parameterTypes); }
    }

    public record StoredSourceTypeIdentity(StoredJavaTypeIdentity javaType, String sourceFile) {
        public StoredSourceTypeIdentity { javaType = Objects.requireNonNull(javaType, "stored Java type is required"); sourceFile = Objects.requireNonNull(sourceFile, "stored source file is required"); }
        static StoredSourceTypeIdentity from(SourceTypeIdentity identity) { return new StoredSourceTypeIdentity(StoredJavaTypeIdentity.from(identity.javaType()), identity.sourceFile()); }
        SourceTypeIdentity toModel() { return new SourceTypeIdentity(javaType.toModel(), sourceFile); }
    }

    public record StoredJavaTypeIdentity(String packageName, String className) {
        public StoredJavaTypeIdentity { packageName = Objects.requireNonNull(packageName, "stored package is required"); className = Objects.requireNonNull(className, "stored class is required"); }
        static StoredJavaTypeIdentity from(JavaTypeIdentity identity) { return new StoredJavaTypeIdentity(identity.packageName(), identity.className()); }
        JavaTypeIdentity toModel() { return new JavaTypeIdentity(packageName, className); }
    }

    public record StoredEntryPointTrigger(boolean hasHttpMethod, String httpMethod, boolean hasHttpPath, String httpPath,
                                          boolean hasDestination, String destinationBroker, String destination, boolean hasSchedule, String schedule) {
        public StoredEntryPointTrigger {
            httpMethod = Objects.requireNonNull(httpMethod, "stored HTTP method is required"); httpPath = Objects.requireNonNull(httpPath, "stored HTTP path is required");
            destinationBroker = Objects.requireNonNull(destinationBroker, "stored destination broker is required"); destination = Objects.requireNonNull(destination, "stored destination is required"); schedule = Objects.requireNonNull(schedule, "stored schedule is required");
        }
        static StoredEntryPointTrigger from(EntryPointTrigger trigger) {
            Optional<ExternalTarget.Destination> target = trigger.destination();
            return new StoredEntryPointTrigger(trigger.httpMethod().isPresent(), trigger.httpMethod().orElse(""), trigger.httpPath().isPresent(),
                    trigger.httpPath().orElse(""), target.isPresent(), target.map(ExternalTarget.Destination::broker).orElse(""),
                    target.map(ExternalTarget.Destination::destination).orElse(""), trigger.schedule().isPresent(), trigger.schedule().orElse(""));
        }
        EntryPointTrigger toModel() {
            Optional<ExternalTarget.Destination> target = hasDestination ? Optional.of(new ExternalTarget.Destination(destinationBroker, destination)) : Optional.empty();
            return new EntryPointTrigger(hasHttpMethod ? Optional.of(httpMethod) : Optional.empty(), hasHttpPath ? Optional.of(httpPath) : Optional.empty(), target,
                    hasSchedule ? Optional.of(schedule) : Optional.empty());
        }
    }

    public record StoredSourceRange(String sourceFile, int startLine, int startCharacter, int endLine, int endCharacter) {
        public StoredSourceRange { sourceFile = Objects.requireNonNull(sourceFile, "stored source file is required"); }
        static StoredSourceRange from(SourceRange range) { return new StoredSourceRange(range.sourceFile(), range.range().start().line(), range.range().start().character(), range.range().end().line(), range.range().end().character()); }
        SourceRange toModel() { return new SourceRange(sourceFile, new SyntaxRange(new SyntaxPosition(startLine, startCharacter), new SyntaxPosition(endLine, endCharacter))); }
    }
}
