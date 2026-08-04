package com.java.semantic.mcp.provider;

import com.java.semantic.mcp.McpQueryProvider;
import com.java.semantic.mcp.McpQueryRegistration;
import com.java.semantic.mcp.dto.concept.ConceptDiscoveryMcpDtos;
import com.java.semantic.mcp.mapper.ConceptDiscoveryMcpMapper;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.application.TypeMemberDiscoveryApplicationService;
import com.java.semantic.syntax.application.TypeMemberQuery;
import com.java.semantic.syntax.application.concept.ConceptDiscoveryApplicationService;
import com.java.semantic.syntax.application.concept.ConceptResolveQuery;
import com.java.semantic.syntax.application.concept.ConceptSearchQuery;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** 發布 concept 與 type member discovery MCP 查詢 */
@Component
public final class ConceptDiscoveryMcpTools implements McpQueryProvider {

    private final ConceptDiscoveryApplicationService conceptDiscoveryApplicationService;
    private final TypeMemberDiscoveryApplicationService typeMemberDiscoveryApplicationService;
    private final ConceptDiscoveryMcpMapper mapper;

    public ConceptDiscoveryMcpTools(
            ConceptDiscoveryApplicationService conceptDiscoveryApplicationService,
            TypeMemberDiscoveryApplicationService typeMemberDiscoveryApplicationService,
            ConceptDiscoveryMcpMapper mapper) {
        this.conceptDiscoveryApplicationService = Objects.requireNonNull(
                conceptDiscoveryApplicationService, "conceptDiscoveryApplicationService is required");
        this.typeMemberDiscoveryApplicationService = Objects.requireNonNull(
                typeMemberDiscoveryApplicationService, "typeMemberDiscoveryApplicationService is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
    }

    @Override
    public List<McpQueryRegistration<?, ?>> registrations() {
        return List.of(
                new McpQueryRegistration<>(
                        "semantic_discover_concepts",
                        "Discover bounded structured concepts at one repository revision",
                        ConceptDiscoveryMcpDtos.DiscoverInput.class,
                        ConceptDiscoveryMcpDtos.DiscoverOutput.class,
                        this::discover),
                new McpQueryRegistration<>(
                        "semantic_resolve_concept",
                        "Resolve one typed concept identity at one repository revision",
                        ConceptDiscoveryMcpDtos.ResolveInput.class,
                        ConceptDiscoveryMcpDtos.ResolveOutput.class,
                        this::resolve),
                new McpQueryRegistration<>(
                        "semantic_discover_type_members",
                        "Discover bounded members for one typed source type",
                        ConceptDiscoveryMcpDtos.TypeMembersInput.class,
                        ConceptDiscoveryMcpDtos.TypeMembersOutput.class,
                        this::typeMembers));
    }

    private ConceptDiscoveryMcpDtos.DiscoverOutput discover(ConceptDiscoveryMcpDtos.DiscoverInput input) {
        return mapper.discover(conceptDiscoveryApplicationService.search(new ConceptSearchQuery(
                RepositoryId.of(input.repoId()),
                new RepositoryRevision(input.expectedRevision()),
                input.terms(),
                input.kinds(),
                input.packagePrefix(),
                input.offset(),
                input.limit())));
    }

    private ConceptDiscoveryMcpDtos.ResolveOutput resolve(ConceptDiscoveryMcpDtos.ResolveInput input) {
        return mapper.resolve(conceptDiscoveryApplicationService.resolve(new ConceptResolveQuery(
                RepositoryId.of(input.repoId()), new RepositoryRevision(input.expectedRevision()), mapper.toDomain(input.identity()))));
    }

    private ConceptDiscoveryMcpDtos.TypeMembersOutput typeMembers(ConceptDiscoveryMcpDtos.TypeMembersInput input) {
        return mapper.typeMembers(typeMemberDiscoveryApplicationService.discover(new TypeMemberQuery(
                RepositoryId.of(input.repoId()),
                new RepositoryRevision(input.expectedRevision()),
                input.sourceType(),
                input.memberKinds(),
                input.namePrefix(),
                input.offset(),
                input.limit())));
    }
}
