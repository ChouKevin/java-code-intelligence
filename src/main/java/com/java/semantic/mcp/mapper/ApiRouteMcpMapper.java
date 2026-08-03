package com.java.semantic.mcp.mapper;

import com.java.semantic.mcp.dto.route.ApiRouteMcpDtos;
import com.java.semantic.trie.ApiRouteMatchBatch;
import org.springframework.stereotype.Component;

/** 將 revision-gated API route 結果投影為 MCP transport DTO */
@Component
public final class ApiRouteMcpMapper {

    public ApiRouteMcpDtos.Output routes(ApiRouteMatchBatch result) {
        return new ApiRouteMcpDtos.Output(result);
    }
}
