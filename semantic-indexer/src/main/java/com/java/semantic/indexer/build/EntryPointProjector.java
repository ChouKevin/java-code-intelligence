package com.java.semantic.indexer.build;

import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.EntryPointIdentity;
import com.java.semantic.model.codefact.EntryPointKind;
import com.java.semantic.model.codefact.EntryPointTrigger;
import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.index.EntryPointDocument;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.syntax.domain.ApiEntryPoint;
import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.MqEntryPoint;
import com.java.semantic.syntax.domain.ScheduleEntryPoint;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Projects framework-recognized entry points without retaining descriptions or swagger text. */
public final class EntryPointProjector {

    public List<EntryPointDocument> project(RepositoryId repositoryId, RepositoryRevision revision, GenerationId generationId,
                                            RepositorySyntax syntax, String sourcePath) {
        List<EntryPointDocument> documents = new ArrayList<>();
        for (EntryPointClass entryPointClass : syntax.entryPoints()) {
            if (!sourcePath.equals(entryPointClass.sourceType().sourceFile())) {
                continue;
            }
            for (com.java.semantic.syntax.domain.EntryPointMethod method : entryPointClass.methods()) {
                MethodTarget target = method.analysisTarget().target().orElseGet(
                        () -> new MethodTarget(entryPointClass.sourceType(), method.name(), List.of()));
                if (method instanceof ApiEntryPoint api) {
                    for (String httpMethod : api.httpMethods()) {
                        documents.add(document(repositoryId, revision, generationId, EntryPointKind.HTTP, target,
                                new EntryPointTrigger(Optional.of(httpMethod), Optional.of(api.apiUrl()), Optional.empty(), Optional.empty()),
                                sourceRange(syntax, entryPointClass.sourceType(), target)));
                    }
                } else if (method instanceof MqEntryPoint mq) {
                    for (String destination : mq.destinations()) {
                        documents.add(document(repositoryId, revision, generationId, EntryPointKind.MQ, target,
                                new EntryPointTrigger(Optional.empty(), Optional.empty(), Optional.of(
                                        new ExternalTarget.Destination(mq.broker().name().toLowerCase(java.util.Locale.ROOT), destination)), Optional.empty()),
                                sourceRange(syntax, entryPointClass.sourceType(), target)));
                    }
                } else if (method instanceof ScheduleEntryPoint schedule) {
                    documents.add(document(repositoryId, revision, generationId, EntryPointKind.SCHEDULE, target,
                            new EntryPointTrigger(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(schedule.triggerValue())),
                            sourceRange(syntax, entryPointClass.sourceType(), target)));
                }
            }
        }
        return documents.stream().sorted(java.util.Comparator.comparing(document -> document.fact().id().value())).toList();
    }

    private static EntryPointDocument document(RepositoryId repositoryId, RepositoryRevision revision, GenerationId generationId,
                                                EntryPointKind kind, MethodTarget method, EntryPointTrigger trigger,
                                                com.java.semantic.model.codefact.SourceRange range) {
        EntryPointIdentity identity = new EntryPointIdentity(kind, method, trigger);
        CodeFactKind factKind = switch (kind) {
            case HTTP -> CodeFactKind.API_ROUTE;
            case MQ -> CodeFactKind.MQ_DESTINATION;
            case SCHEDULE -> CodeFactKind.SCHEDULE;
        };
        CodeFact fact = SyntaxSymbolProjector.fact(repositoryId, revision, factKind, identity);
        return new EntryPointDocument(repositoryId, generationId, fact, kind, method, trigger, range);
    }

    private static com.java.semantic.model.codefact.SourceRange sourceRange(RepositorySyntax syntax, SourceTypeIdentity type,
                                                                              MethodTarget target) {
        return syntax.sourceTypes().stream().filter(metadata -> metadata.declaration().identity().equals(type))
                .flatMap(metadata -> metadata.members().methods().stream())
                .filter(method -> matches(method, target)).map(SourceMethodMetadata::declarationLocation).findFirst()
                .orElseThrow(() -> new IllegalStateException("entry-point method must have an authoritative source range"));
    }

    private static boolean matches(SourceMethodMetadata method, MethodTarget target) {
        return method.analysisTarget().target().map(target::equals)
                .orElseGet(() -> method.name().equals(target.methodName()) && method.paramTypes().equals(target.parameterTypes()));
    }
}
