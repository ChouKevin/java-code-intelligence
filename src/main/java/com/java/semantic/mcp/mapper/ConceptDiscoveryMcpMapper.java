package com.java.semantic.mcp.mapper;

import com.java.semantic.mcp.dto.concept.ConceptDiscoveryMcpDtos;
import com.java.semantic.syntax.application.TypeMemberResult;
import com.java.semantic.syntax.application.concept.ConceptSearchResult;
import com.java.semantic.syntax.application.concept.RevisionBoundConceptResolution;
import org.springframework.stereotype.Component;

/** 將 concept discovery 結果投影為 MCP transport DTO */
@Component
public final class ConceptDiscoveryMcpMapper {

    public ConceptDiscoveryMcpDtos.DiscoverOutput discover(ConceptSearchResult result) {
        return new ConceptDiscoveryMcpDtos.DiscoverOutput(result);
    }

    public ConceptDiscoveryMcpDtos.ResolveOutput resolve(RevisionBoundConceptResolution result) {
        return new ConceptDiscoveryMcpDtos.ResolveOutput(result);
    }

    public ConceptDiscoveryMcpDtos.TypeMembersOutput typeMembers(TypeMemberResult result) {
        return new ConceptDiscoveryMcpDtos.TypeMembersOutput(result);
    }
}
