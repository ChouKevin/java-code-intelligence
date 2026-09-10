package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactReadQuery;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactDetails;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
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
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.query.CurrentGeneration;
import com.java.semantic.model.query.PublishedRelationQuery;
import com.java.semantic.model.query.PublishedRelationResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Transport-neutral application facade for repository, exact-source, and code-fact queries. */
public final class SemanticQueryFacade {
    private final CurrentRepositoryQueryService repositoryQueryService;
    private final CodeFactSearchService codeFactSearchService;
    private final SourceSliceService sourceSliceService;
    private final CodeFactReadService codeFactReadService;
    private final PublishedDiscoveryQueryService discoveryQueryService;
    private final PublishedEntryPointQueryService entryPointQueryService;
    private final PublishedRelationQueryService relationQueryService;

    public SemanticQueryFacade(CurrentRepositoryQueryService repositoryQueryService, CodeFactSearchService codeFactSearchService,
                               SourceSliceService sourceSliceService, CodeFactReadService codeFactReadService,
                               PublishedDiscoveryQueryService discoveryQueryService,
                               PublishedEntryPointQueryService entryPointQueryService,
                               PublishedRelationQueryService relationQueryService) {
        this.repositoryQueryService = Objects.requireNonNull(repositoryQueryService, "repository query service is required");
        this.codeFactSearchService = Objects.requireNonNull(codeFactSearchService, "code fact search service is required");
        this.sourceSliceService = Objects.requireNonNull(sourceSliceService, "source slice service is required");
        this.codeFactReadService = Objects.requireNonNull(codeFactReadService, "code fact read service is required");
        this.discoveryQueryService = Objects.requireNonNull(discoveryQueryService, "discovery query service is required");
        this.entryPointQueryService = Objects.requireNonNull(entryPointQueryService, "entry point query service is required");
        this.relationQueryService = Objects.requireNonNull(relationQueryService, "relation query service is required");
    }

    public SemanticQueryContract.RepositoryCollection listRepositories(SemanticQueryContract.PageRequest request) {
        SemanticQueryContract.PageRequest requiredRequest = Objects.requireNonNull(request, "page request is required");
        List<SemanticQueryContract.RepositoryItem> repositories = repositoryQueryService.listRepositories().stream()
                .sorted(Comparator.comparing(current -> current.repositoryId().value()))
                .map(SemanticResultMapper::toRepositoryItem)
                .toList();
        int start = Math.min(requiredRequest.offset(), repositories.size());
        int end = Math.min(start + requiredRequest.limit(), repositories.size());
        List<SemanticQueryContract.RepositoryItem> items = List.copyOf(repositories.subList(start, end));
        SemanticQueryContract.Page page = new SemanticQueryContract.Page(requiredRequest.offset(), requiredRequest.limit(), items.size(),
                repositories.size(), end < repositories.size());
        return new SemanticQueryContract.RepositoryCollection(items, page);
    }

    public SemanticQueryContract.RepositoryItem getRepository(SemanticQueryContract.RepositoryRequest request) {
        SemanticQueryContract.RepositoryRequest requiredRequest = Objects.requireNonNull(request, "repository request is required");
        CurrentGeneration generation = repositoryQueryService.getRepository(requiredRequest.repositoryId());
        return SemanticResultMapper.toRepositoryItem(generation);
    }

    public SemanticQueryContract.SearchCodeResult searchCode(SemanticQueryContract.SearchCodeRequest request) {
        SemanticQueryContract.SearchCodeRequest requiredRequest = Objects.requireNonNull(request, "search code request is required");
        CodeFactSearchQuery query = new CodeFactSearchQuery(new RepositoryId(requiredRequest.repositoryId()),
                new RepositoryRevision(requiredRequest.revision()), requiredRequest.query(), requiredRequest.kinds(),
                requiredRequest.packagePrefix(), requiredRequest.offset(), requiredRequest.limit());
        CodeFactSearchResult result = codeFactSearchService.search(query);
        List<SemanticQueryContract.ProgramElement> elements = new ArrayList<>();
        for (CodeFactSummary summary : result.facts()) {
            CodeFactReadQuery sourceQuery = new CodeFactReadQuery(result.generation().repositoryId(), result.generation().revision(),
                    summary.fact().id());
            FactSourceSlice source = sourceSliceService.factSource(sourceQuery, 0);
            elements.add(SemanticResultMapper.toProgramElement(summary, source));
        }
        return SemanticResultMapper.toSearchCodeResult(result, elements);
    }

    public SemanticQueryContract.FactSourceResult getFactSource(SemanticQueryContract.FactSourceRequest request) {
        SemanticQueryContract.FactSourceRequest requiredRequest = Objects.requireNonNull(request, "fact source request is required");
        CodeFactReadQuery query = new CodeFactReadQuery(new RepositoryId(requiredRequest.repositoryId()),
                new RepositoryRevision(requiredRequest.revision()), new CodeFactId(requiredRequest.factId()));
        FactSourceSlice source = sourceSliceService.factSource(query, requiredRequest.contextLines());
        return SemanticResultMapper.toFactSourceResult(requiredRequest.factId(), source);
    }

    public SemanticQueryContract.CollectionResult listEntryPoints(SemanticQueryContract.EntryPointRequest request) {
        SemanticQueryContract.EntryPointRequest requiredRequest = Objects.requireNonNull(request, "entry point request is required");
        Set<EntryPointKind> kinds = requiredRequest.kinds().isEmpty() ? EnumSet.allOf(EntryPointKind.class) : requiredRequest.kinds();
        PublishedEntryPointResult result = entryPointQueryService.listEntryPoints(requiredRequest.repositoryId(), requiredRequest.revision(),
                kinds, requiredRequest.offset(), requiredRequest.limit());
        List<SemanticQueryContract.EntryPointItem> items = new ArrayList<>();
        for (PublishedEntryPoint entryPoint : result.entryPoints()) {
            items.add(toEntryPointItem(result.generation(), entryPoint.factId()));
        }
        return SemanticResultMapper.toCollectionResult(result.generation(), requiredRequest.offset(), requiredRequest.limit(), items,
                result.totalCount(), result.hasMore());
    }

    public SemanticQueryContract.CollectionResult findApiRoutes(SemanticQueryContract.ApiRouteRequest request) {
        SemanticQueryContract.ApiRouteRequest requiredRequest = Objects.requireNonNull(request, "API route request is required");
        PublishedEntryPointResult result = entryPointQueryService.findRoutes(requiredRequest.repositoryId(), requiredRequest.revision(),
                requiredRequest.httpMethod().name(), requiredRequest.path(), requiredRequest.offset(), requiredRequest.limit());
        List<SemanticQueryContract.EntryPointItem> items = new ArrayList<>();
        for (PublishedEntryPoint entryPoint : result.entryPoints()) {
            items.add(toEntryPointItem(result.generation(), entryPoint.factId()));
        }
        return SemanticResultMapper.toCollectionResult(result.generation(), requiredRequest.offset(), requiredRequest.limit(), items,
                result.totalCount(), result.hasMore());
    }

    public SemanticQueryContract.CollectionResult findEventListeners(SemanticQueryContract.EventListenerRequest request) {
        SemanticQueryContract.EventListenerRequest requiredRequest = Objects.requireNonNull(request, "event listener request is required");
        EventListenerResult result = discoveryQueryService.discoverEventListeners(new EventListenerQuery(
                new RepositoryId(requiredRequest.repositoryId()), new RepositoryRevision(requiredRequest.revision()), requiredRequest.eventType(),
                requiredRequest.offset(), requiredRequest.limit()));
        List<SemanticQueryContract.EventListenerItem> items = new ArrayList<>();
        for (EventListenerCandidate candidate : result.candidates()) {
            CodeFactDetails handler = readMethod(result.generation(), candidate.target());
            FactSourceSlice source = sourceSliceService.factSource(readQuery(result.generation(), handler.fact().id().value()), 0);
            items.add(new SemanticQueryContract.EventListenerItem(requiredRequest.eventType(), SemanticResultMapper.toProgramElement(handler, source)));
        }
        return SemanticResultMapper.toCollectionResult(result.generation(), requiredRequest.offset(), requiredRequest.limit(), items,
                result.totalCount(), result.hasMore());
    }

    public SemanticQueryContract.CollectionResult listTypeMembers(SemanticQueryContract.TypeMemberRequest request) {
        SemanticQueryContract.TypeMemberRequest requiredRequest = Objects.requireNonNull(request, "type member request is required");
        CodeFactDetails type = codeFactReadService.get(readQuery(requiredRequest.repositoryId(), requiredRequest.revision(),
                requiredRequest.typeFactId()));
        if (!FactKindPolicy.TYPE_MEMBERS.contains(type.fact().identity().kind())) {
            throw new CodeFactKindMismatchException();
        }
        if (!(type.fact().identity().canonicalIdentity() instanceof SourceTypeIdentity sourceType)) {
            throw new IndexContractMismatchException();
        }
        Set<CodeFactKind> kinds = requiredRequest.kinds().isEmpty() ? TypeMemberQuery.MEMBER_KINDS : requiredRequest.kinds();
        TypeMemberResult result = discoveryQueryService.discoverTypeMembers(new TypeMemberQuery(new RepositoryId(requiredRequest.repositoryId()),
                new RepositoryRevision(requiredRequest.revision()), sourceType, kinds, requiredRequest.offset(), requiredRequest.limit()));
        List<SemanticQueryContract.ProgramElement> items = new ArrayList<>();
        for (CodeFactSummary member : result.members()) {
            FactSourceSlice source = sourceSliceService.factSource(readQuery(result.generation(), member.fact().id().value()), 0);
            items.add(SemanticResultMapper.toProgramElement(member, source));
        }
        return SemanticResultMapper.toCollectionResult(result.generation(), requiredRequest.offset(), requiredRequest.limit(), items,
                result.totalCount(), result.hasMore());
    }

    public SemanticQueryContract.CollectionResult findMethodImplementations(SemanticQueryContract.RelationRequest request) {
        SemanticQueryContract.RelationRequest requiredRequest = Objects.requireNonNull(request, "relation request is required");
        CodeFactDetails target = requireRelationTarget(requiredRequest, FactKindPolicy.METHOD_IMPLEMENTATIONS);
        PublishedRelationResult result = relationQueryService.findImplementations(relationQuery(requiredRequest, target));
        List<SemanticQueryContract.ImplementationItem> items = new ArrayList<>();
        for (com.java.semantic.model.index.RelationDocument relation : result.relations()) {
            items.add(new SemanticQueryContract.ImplementationItem(programElement(result.generation(), relation.from()), relation.kind()));
        }
        return relationCollection(result, requiredRequest, items);
    }

    public SemanticQueryContract.CollectionResult findReferences(SemanticQueryContract.RelationRequest request) {
        SemanticQueryContract.RelationRequest requiredRequest = Objects.requireNonNull(request, "relation request is required");
        CodeFactDetails target = requireRelationTarget(requiredRequest, FactKindPolicy.REFERENCE_TARGETS);
        PublishedRelationResult result = relationQueryService.findReferences(relationQuery(requiredRequest, target));
        List<SemanticQueryContract.ReferenceItem> items = new ArrayList<>();
        for (com.java.semantic.model.index.RelationDocument relation : result.relations()) {
            items.add(new SemanticQueryContract.ReferenceItem(programElement(result.generation(), relation.from()), callSite(result.generation(), relation)));
        }
        return relationCollection(result, requiredRequest, items);
    }

    public SemanticQueryContract.CollectionResult findCallers(SemanticQueryContract.RelationRequest request) {
        SemanticQueryContract.RelationRequest requiredRequest = Objects.requireNonNull(request, "relation request is required");
        CodeFactDetails target = requireRelationTarget(requiredRequest, FactKindPolicy.CALLERS);
        PublishedRelationResult result = relationQueryService.findCallers(relationQuery(requiredRequest, target));
        List<SemanticQueryContract.CallerItem> items = new ArrayList<>();
        for (com.java.semantic.model.index.RelationDocument relation : result.relations()) {
            items.add(new SemanticQueryContract.CallerItem(programElement(result.generation(), relation.from()), callSite(result.generation(), relation)));
        }
        return relationCollection(result, requiredRequest, items);
    }

    public SemanticQueryContract.CollectionResult findCallees(SemanticQueryContract.RelationRequest request) {
        SemanticQueryContract.RelationRequest requiredRequest = Objects.requireNonNull(request, "relation request is required");
        CodeFactDetails target = requireRelationTarget(requiredRequest, FactKindPolicy.CALLEES);
        PublishedRelationResult result = relationQueryService.findCallees(relationQuery(requiredRequest, target));
        List<SemanticQueryContract.CalleeItem> items = new ArrayList<>();
        for (com.java.semantic.model.index.RelationDocument relation : result.relations()) {
            SemanticQueryContract.CalleeResolutionStatus resolutionStatus = calleeResolutionStatus(relation.target());
            items.add(new SemanticQueryContract.CalleeItem(callee(result.generation(), relation.target()),
                    callSite(result.generation(), relation), resolutionStatus));
        }
        return relationCollection(result, requiredRequest, items);
    }

    private SemanticQueryContract.EntryPointItem toEntryPointItem(CurrentGeneration generation, String entryPointFactId) {
        CodeFactDetails entryPoint = codeFactReadService.get(readQuery(generation, entryPointFactId));
        if (!(entryPoint.fact().identity().canonicalIdentity() instanceof EntryPointIdentity identity)) {
            throw new IndexContractMismatchException();
        }
        CodeFactDetails handler = readMethod(generation, identity.method());
        FactSourceSlice source = sourceSliceService.factSource(readQuery(generation, handler.fact().id().value()), 0);
        return new SemanticQueryContract.EntryPointItem(entryPointFactId, SemanticResultMapper.toProgramElement(handler, source),
                toTrigger(identity.entryPointKind(), identity.trigger()));
    }

    private CodeFactDetails readMethod(CurrentGeneration generation, MethodTarget target) {
        CodeFactIdentity identity = new CodeFactIdentity(generation.repositoryId(), generation.revision(), CodeFactKind.METHOD, target);
        CodeFactDetails details = codeFactReadService.get(readQuery(generation, CodeFactId.from(identity).value()));
        if (details.fact().identity().kind() != CodeFactKind.METHOD) {
            throw new IndexContractMismatchException();
        }
        return details;
    }

    private CodeFactDetails requireRelationTarget(SemanticQueryContract.RelationRequest request, Set<CodeFactKind> acceptedKinds) {
        return FactKindPolicy.require(codeFactReadService.get(readQuery(request.repositoryId(), request.revision(), request.factId())), acceptedKinds);
    }

    private static PublishedRelationQuery relationQuery(SemanticQueryContract.RelationRequest request, CodeFactDetails target) {
        return new PublishedRelationQuery(new RepositoryId(request.repositoryId()), new RepositoryRevision(request.revision()),
                target.fact().identity(), request.offset(), request.limit());
    }

    private SemanticQueryContract.ProgramElement programElement(CurrentGeneration generation, CodeFactIdentity identity) {
        CodeFactDetails details = codeFactReadService.get(readQuery(generation, CodeFactId.from(identity).value()));
        FactSourceSlice source = sourceSliceService.factSource(readQuery(generation, details.fact().id().value()), 0);
        return SemanticResultMapper.toProgramElement(details, source);
    }

    private SemanticQueryContract.ProgramElement callee(CurrentGeneration generation, RelationTarget target) {
        if (target instanceof RelationTarget.Internal internalTarget) {
            return programElement(generation, internalTarget.identity());
        }
        if (target instanceof RelationTarget.External externalTarget) {
            return new SemanticQueryContract.ProgramElement(null, null, externalTarget.target().canonicalForm(), null);
        }
        throw new IndexContractMismatchException();
    }

    private static SemanticQueryContract.CalleeResolutionStatus calleeResolutionStatus(RelationTarget target) {
        if (target instanceof RelationTarget.Internal) {
            return SemanticQueryContract.CalleeResolutionStatus.INDEXED;
        }
        if (target instanceof RelationTarget.External externalTarget) {
            if (externalTarget.target() instanceof ExternalTarget.UnresolvedCall) {
                return SemanticQueryContract.CalleeResolutionStatus.UNRESOLVED;
            }
            return SemanticQueryContract.CalleeResolutionStatus.UNINDEXED_TARGET;
        }
        throw new IndexContractMismatchException();
    }

    private SemanticQueryContract.RelationSite callSite(CurrentGeneration generation,
                                                        com.java.semantic.model.index.RelationDocument relation) {
        FactSourceSlice source = sourceSliceService.factSource(readQuery(generation, relation.fact().id().value()), 0);
        return new SemanticQueryContract.RelationSite(relation.fact().id().value(),
                SourceSnippetMapper.toSnippet(source.sourceRange(), source.fileContent()));
    }

    private static SemanticQueryContract.CollectionResult relationCollection(PublishedRelationResult result,
                                                                              SemanticQueryContract.RelationRequest request,
                                                                              List<?> items) {
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

    private static CodeFactReadQuery readQuery(CurrentGeneration generation, String factId) {
        return readQuery(generation.repositoryId().value(), generation.revision().value(), factId);
    }

    private static CodeFactReadQuery readQuery(String repositoryId, String revision, String factId) {
        return new CodeFactReadQuery(new RepositoryId(repositoryId), new RepositoryRevision(revision), new CodeFactId(factId));
    }
}
