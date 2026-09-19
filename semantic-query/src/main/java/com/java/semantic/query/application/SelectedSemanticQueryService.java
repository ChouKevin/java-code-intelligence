package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactDetails;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.CodeFactReadQuery;
import com.java.semantic.model.codefact.CodeFactSearchQuery;
import com.java.semantic.model.codefact.CodeFactSearchResult;
import com.java.semantic.model.codefact.CodeFactSummary;
import com.java.semantic.model.codefact.EntryPointIdentity;
import com.java.semantic.model.codefact.EntryPointKind;
import com.java.semantic.model.codefact.EntryPointTrigger;
import com.java.semantic.model.codefact.EventListenerCandidate;
import com.java.semantic.model.codefact.EventListenerQuery;
import com.java.semantic.model.codefact.EventListenerResult;
import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.PublishedEntryPoint;
import com.java.semantic.model.codefact.RelationTarget;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.codefact.TypeMemberQuery;
import com.java.semantic.model.codefact.TypeMemberResult;
import com.java.semantic.model.query.PublishedRelationQuery;
import com.java.semantic.model.query.PublishedRelationResult;
import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.java.semantic.query.application.SemanticQueryContract.ApiRouteRequest;
import static com.java.semantic.query.application.SemanticQueryContract.CalleeResolutionStatus;
import static com.java.semantic.query.application.SemanticQueryContract.CollectionResult;
import static com.java.semantic.query.application.SemanticQueryContract.EntryPointRequest;
import static com.java.semantic.query.application.SemanticQueryContract.EventListenerRequest;
import static com.java.semantic.query.application.SemanticQueryContract.FactSourceRequest;
import static com.java.semantic.query.application.SemanticQueryContract.FactSourceResult;
import static com.java.semantic.query.application.SemanticQueryContract.RelationRequest;
import static com.java.semantic.query.application.SemanticQueryContract.SearchCodeRequest;
import static com.java.semantic.query.application.SemanticQueryContract.SearchCodeResult;
import static com.java.semantic.query.application.SemanticQueryContract.TypeMemberRequest;

/** Materializes semantic query results against one generation selected at facade admission. */
public final class SelectedSemanticQueryService {
    private final CodeFactSearchService searchService;
    private final SourceSliceService sourceSliceService;
    private final CodeFactReadService codeFactReadService;
    private final PublishedDiscoveryQueryService discoveryQueryService;
    private final PublishedEntryPointQueryService entryPointQueryService;
    private final PublishedRelationQueryService relationQueryService;

    public SelectedSemanticQueryService(CodeFactSearchService searchService, SourceSliceService sourceSliceService,
                                        CodeFactReadService codeFactReadService, PublishedDiscoveryQueryService discoveryQueryService,
                                        PublishedEntryPointQueryService entryPointQueryService,
                                        PublishedRelationQueryService relationQueryService) {
        this.searchService = Objects.requireNonNull(searchService, "search service is required");
        this.sourceSliceService = Objects.requireNonNull(sourceSliceService, "source slice service is required");
        this.codeFactReadService = Objects.requireNonNull(codeFactReadService, "code fact read service is required");
        this.discoveryQueryService = Objects.requireNonNull(discoveryQueryService, "discovery query service is required");
        this.entryPointQueryService = Objects.requireNonNull(entryPointQueryService, "entry point query service is required");
        this.relationQueryService = Objects.requireNonNull(relationQueryService, "relation query service is required");
    }

    public static SelectedSemanticQueryService create(org.springframework.data.mongodb.core.MongoTemplate template,
                                                       SelectedGenerationGuard guard, Duration storageTimeout) {
        CodeFactReadService factReader = new CodeFactReadService(template, guard, storageTimeout);
        CurrentSourceQueryService sourceReader = new CurrentSourceQueryService(template, guard, storageTimeout);
        return new SelectedSemanticQueryService(new CodeFactSearchService(template, guard, storageTimeout),
                new SourceSliceService(sourceReader, factReader), factReader,
                new PublishedDiscoveryQueryService(template, guard, storageTimeout),
                new PublishedEntryPointQueryService(template, guard, storageTimeout),
                new PublishedRelationQueryService(template, guard, storageTimeout));
    }

    public SearchCodeResult searchCode(SelectedGeneration context, SearchCodeRequest request) {
        SelectedGeneration selected = Objects.requireNonNull(context, "selected generation is required");
        SearchCodeRequest requiredRequest = Objects.requireNonNull(request, "search request is required");
        CodeFactSearchQuery query = new CodeFactSearchQuery(new RepositoryId(requiredRequest.repositoryId()),
                new RepositoryRevision(requiredRequest.revision()), requiredRequest.query(), requiredRequest.kinds(),
                requiredRequest.packagePrefix(), requiredRequest.offset(), requiredRequest.limit());
        CodeFactSearchResult result = searchService.search(selected, query);
        List<SemanticQueryContract.ProgramElement> items = new ArrayList<>();
        for (CodeFactSummary summary : result.facts()) {
            FactSourceSlice source = sourceSliceService.factSource(selected, readQuery(selected, summary.fact().id().value()), 0);
            items.add(SemanticResultMapper.toProgramElement(summary, source));
        }
        return SemanticResultMapper.toSearchCodeResult(result, items);
    }

    public FactSourceResult getFactSource(SelectedGeneration context, FactSourceRequest request) {
        FactSourceRequest requiredRequest = Objects.requireNonNull(request, "fact source request is required");
        FactSourceSlice source = sourceSliceService.factSource(context, readQuery(requiredRequest.repositoryId(), requiredRequest.revision(),
                requiredRequest.factId()), requiredRequest.contextLines());
        return SemanticResultMapper.toFactSourceResult(requiredRequest.factId(), source);
    }

    public CollectionResult listEntryPoints(SelectedGeneration context, EntryPointRequest request) {
        EntryPointRequest requiredRequest = Objects.requireNonNull(request, "entry point request is required");
        Set<EntryPointKind> kinds = requiredRequest.kinds().isEmpty() ? EnumSet.allOf(EntryPointKind.class) : requiredRequest.kinds();
        PublishedEntryPointResult result = entryPointQueryService.listEntryPoints(context, requiredRequest.repositoryId(),
                requiredRequest.revision(), kinds, requiredRequest.offset(), requiredRequest.limit());
        List<SemanticQueryContract.EntryPointItem> items = new ArrayList<>();
        for (PublishedEntryPoint entryPoint : result.entryPoints()) {
            items.add(toEntryPointItem(context, entryPoint.factId()));
        }
        return SemanticResultMapper.toCollectionResult(result.generation(), requiredRequest.offset(), requiredRequest.limit(), items,
                result.totalCount(), result.hasMore());
    }

    public CollectionResult findApiRoutes(SelectedGeneration context, ApiRouteRequest request) {
        ApiRouteRequest requiredRequest = Objects.requireNonNull(request, "API route request is required");
        PublishedEntryPointResult result = entryPointQueryService.findRoutes(context, requiredRequest.repositoryId(), requiredRequest.revision(),
                requiredRequest.httpMethod().name(), requiredRequest.path(), requiredRequest.offset(), requiredRequest.limit());
        List<SemanticQueryContract.EntryPointItem> items = new ArrayList<>();
        for (PublishedEntryPoint entryPoint : result.entryPoints()) {
            items.add(toEntryPointItem(context, entryPoint.factId()));
        }
        return SemanticResultMapper.toCollectionResult(result.generation(), requiredRequest.offset(), requiredRequest.limit(), items,
                result.totalCount(), result.hasMore());
    }

    public CollectionResult findEventListeners(SelectedGeneration context, EventListenerRequest request) {
        EventListenerRequest requiredRequest = Objects.requireNonNull(request, "event listener request is required");
        EventListenerResult result = discoveryQueryService.discoverEventListeners(context, new EventListenerQuery(
                new RepositoryId(requiredRequest.repositoryId()), new RepositoryRevision(requiredRequest.revision()), requiredRequest.eventType(),
                requiredRequest.offset(), requiredRequest.limit()));
        List<SemanticQueryContract.EventListenerItem> items = new ArrayList<>();
        for (EventListenerCandidate candidate : result.candidates()) {
            CodeFactDetails handler = readMethod(context, candidate.target());
            FactSourceSlice source = sourceSliceService.factSource(context, readQuery(context, handler.fact().id().value()), 0);
            items.add(new SemanticQueryContract.EventListenerItem(requiredRequest.eventType(), SemanticResultMapper.toProgramElement(handler, source)));
        }
        return SemanticResultMapper.toCollectionResult(result.generation(), requiredRequest.offset(), requiredRequest.limit(), items,
                result.totalCount(), result.hasMore());
    }

    public CollectionResult listTypeMembers(SelectedGeneration context, TypeMemberRequest request) {
        TypeMemberRequest requiredRequest = Objects.requireNonNull(request, "type member request is required");
        CodeFactDetails type = codeFactReadService.get(context, readQuery(requiredRequest.repositoryId(), requiredRequest.revision(),
                requiredRequest.typeFactId()));
        if (!FactKindPolicy.TYPE_MEMBERS.contains(type.fact().identity().kind())) {
            throw new CodeFactKindMismatchException();
        }
        if (!(type.fact().identity().canonicalIdentity() instanceof SourceTypeIdentity sourceType)) {
            throw new IndexContractMismatchException();
        }
        Set<CodeFactKind> kinds = requiredRequest.kinds().isEmpty() ? TypeMemberQuery.MEMBER_KINDS : requiredRequest.kinds();
        TypeMemberResult result = discoveryQueryService.discoverTypeMembers(context, new TypeMemberQuery(
                new RepositoryId(requiredRequest.repositoryId()), new RepositoryRevision(requiredRequest.revision()), sourceType, kinds,
                requiredRequest.offset(), requiredRequest.limit()));
        List<SemanticQueryContract.ProgramElement> items = new ArrayList<>();
        for (CodeFactSummary member : result.members()) {
            FactSourceSlice source = sourceSliceService.factSource(context, readQuery(context, member.fact().id().value()), 0);
            items.add(SemanticResultMapper.toProgramElement(member, source));
        }
        return SemanticResultMapper.toCollectionResult(result.generation(), requiredRequest.offset(), requiredRequest.limit(), items,
                result.totalCount(), result.hasMore());
    }

    public CollectionResult findMethodImplementations(SelectedGeneration context, RelationRequest request) {
        RelationRequest requiredRequest = Objects.requireNonNull(request, "relation request is required");
        PublishedRelationResult result = relationQueryService.findImplementations(context,
                relationQuery(requiredRequest, requireRelationTarget(context, requiredRequest, FactKindPolicy.METHOD_IMPLEMENTATIONS)));
        List<SemanticQueryContract.ImplementationItem> items = new ArrayList<>();
        for (com.java.semantic.model.index.RelationDocument relation : result.relations()) {
            items.add(new SemanticQueryContract.ImplementationItem(programElement(context, relation.from()), relation.kind()));
        }
        return relationCollection(result, requiredRequest, items);
    }

    public CollectionResult findReferences(SelectedGeneration context, RelationRequest request) {
        RelationRequest requiredRequest = Objects.requireNonNull(request, "relation request is required");
        PublishedRelationResult result = relationQueryService.findReferences(context,
                relationQuery(requiredRequest, requireRelationTarget(context, requiredRequest, FactKindPolicy.REFERENCE_TARGETS)));
        List<SemanticQueryContract.ReferenceItem> items = new ArrayList<>();
        for (com.java.semantic.model.index.RelationDocument relation : result.relations()) {
            items.add(new SemanticQueryContract.ReferenceItem(programElement(context, relation.from()), callSite(context, relation)));
        }
        return relationCollection(result, requiredRequest, items);
    }

    public CollectionResult findCallers(SelectedGeneration context, RelationRequest request) {
        RelationRequest requiredRequest = Objects.requireNonNull(request, "relation request is required");
        PublishedRelationResult result = relationQueryService.findCallers(context,
                relationQuery(requiredRequest, requireRelationTarget(context, requiredRequest, FactKindPolicy.CALLERS)));
        List<SemanticQueryContract.CallerItem> items = new ArrayList<>();
        for (com.java.semantic.model.index.RelationDocument relation : result.relations()) {
            items.add(new SemanticQueryContract.CallerItem(programElement(context, relation.from()), callSite(context, relation)));
        }
        return relationCollection(result, requiredRequest, items);
    }

    public CollectionResult findCallees(SelectedGeneration context, RelationRequest request) {
        RelationRequest requiredRequest = Objects.requireNonNull(request, "relation request is required");
        PublishedRelationResult result = relationQueryService.findCallees(context,
                relationQuery(requiredRequest, requireRelationTarget(context, requiredRequest, FactKindPolicy.CALLEES)));
        List<SemanticQueryContract.CalleeItem> items = new ArrayList<>();
        for (com.java.semantic.model.index.RelationDocument relation : result.relations()) {
            items.add(new SemanticQueryContract.CalleeItem(callee(context, relation.target()), callSite(context, relation),
                    calleeResolutionStatus(relation.target())));
        }
        return relationCollection(result, requiredRequest, items);
    }

    private SemanticQueryContract.EntryPointItem toEntryPointItem(SelectedGeneration context, String entryPointFactId) {
        CodeFactDetails entryPoint = codeFactReadService.get(context, readQuery(context, entryPointFactId));
        if (!(entryPoint.fact().identity().canonicalIdentity() instanceof EntryPointIdentity identity)) {
            throw new IndexContractMismatchException();
        }
        CodeFactDetails handler = readMethod(context, identity.method());
        FactSourceSlice source = sourceSliceService.factSource(context, readQuery(context, handler.fact().id().value()), 0);
        return new SemanticQueryContract.EntryPointItem(entryPointFactId, SemanticResultMapper.toProgramElement(handler, source),
                toTrigger(identity.entryPointKind(), identity.trigger()));
    }

    private CodeFactDetails readMethod(SelectedGeneration context, MethodTarget target) {
        CodeFactIdentity identity = new CodeFactIdentity(context.repositoryId(), context.revision(), CodeFactKind.METHOD, target);
        CodeFactDetails details = codeFactReadService.get(context, readQuery(context, CodeFactId.from(identity).value()));
        if (details.fact().identity().kind() != CodeFactKind.METHOD) {
            throw new IndexContractMismatchException();
        }
        return details;
    }

    private CodeFactDetails requireRelationTarget(SelectedGeneration context, RelationRequest request, Set<CodeFactKind> acceptedKinds) {
        return FactKindPolicy.require(codeFactReadService.get(context, readQuery(request.repositoryId(), request.revision(), request.factId())),
                acceptedKinds);
    }

    private static PublishedRelationQuery relationQuery(RelationRequest request, CodeFactDetails target) {
        return new PublishedRelationQuery(new RepositoryId(request.repositoryId()), new RepositoryRevision(request.revision()),
                target.fact().identity(), request.offset(), request.limit());
    }

    private SemanticQueryContract.ProgramElement programElement(SelectedGeneration context, CodeFactIdentity identity) {
        CodeFactDetails details = codeFactReadService.get(context, readQuery(context, CodeFactId.from(identity).value()));
        FactSourceSlice source = sourceSliceService.factSource(context, readQuery(context, details.fact().id().value()), 0);
        return SemanticResultMapper.toProgramElement(details, source);
    }

    private SemanticQueryContract.ProgramElement callee(SelectedGeneration context, RelationTarget target) {
        if (target instanceof RelationTarget.Internal internalTarget) {
            return programElement(context, internalTarget.identity());
        }
        if (target instanceof RelationTarget.External externalTarget) {
            return new SemanticQueryContract.ProgramElement(null, null, externalTarget.target().canonicalForm(), null);
        }
        throw new IndexContractMismatchException();
    }

    private static CalleeResolutionStatus calleeResolutionStatus(RelationTarget target) {
        if (target instanceof RelationTarget.Internal) {
            return CalleeResolutionStatus.INDEXED;
        }
        if (target instanceof RelationTarget.External externalTarget) {
            if (externalTarget.target() instanceof ExternalTarget.UnresolvedCall) {
                return CalleeResolutionStatus.UNRESOLVED;
            }
            return CalleeResolutionStatus.UNINDEXED_TARGET;
        }
        throw new IndexContractMismatchException();
    }

    private SemanticQueryContract.RelationSite callSite(SelectedGeneration context,
                                                        com.java.semantic.model.index.RelationDocument relation) {
        FactSourceSlice source = sourceSliceService.factSource(context, readQuery(context, relation.fact().id().value()), 0);
        return new SemanticQueryContract.RelationSite(relation.fact().id().value(),
                SourceSnippetMapper.toSnippet(source.sourceRange(), source.fileContent()));
    }

    private static CollectionResult relationCollection(PublishedRelationResult result, RelationRequest request, List<?> items) {
        return SemanticResultMapper.toCollectionResult(result.generation(), request.offset(), request.limit(), items,
                result.page().totalCount(), result.page().hasMore());
    }

    private static SemanticQueryContract.Trigger toTrigger(EntryPointKind kind, EntryPointTrigger trigger) {
        return switch (kind) {
            case HTTP -> new SemanticQueryContract.Trigger(kind.name(), trigger.httpMethod().orElseThrow(IndexContractMismatchException::new),
                    trigger.httpPath().orElseThrow(IndexContractMismatchException::new));
            case MQ -> new SemanticQueryContract.Trigger(kind.name(), "", trigger.destination()
                    .map(destination -> destination.canonicalForm()).orElseThrow(IndexContractMismatchException::new));
            case SCHEDULE -> new SemanticQueryContract.Trigger(kind.name(), "", trigger.schedule().orElseThrow(IndexContractMismatchException::new));
        };
    }

    private static CodeFactReadQuery readQuery(SelectedGeneration generation, String factId) {
        return readQuery(generation.repositoryId().value(), generation.revision().value(), factId);
    }

    private static CodeFactReadQuery readQuery(String repositoryId, String revision, String factId) {
        return new CodeFactReadQuery(new RepositoryId(repositoryId), new RepositoryRevision(revision), new CodeFactId(factId));
    }
}
