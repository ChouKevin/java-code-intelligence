package com.java.semantic.mcp.mapper;

import com.java.semantic.callgraph.domain.IncomingGraphFragment;
import com.java.semantic.callgraph.domain.OutgoingGraphFragment;
import com.java.semantic.mcp.dto.callgraph.CallGraphMcpDtos;
import org.springframework.stereotype.Component;

/** 將 call graph application 結果投影為 MCP transport DTO */
@Component
public final class CallGraphMcpMapper {

    public CallGraphMcpDtos.OutgoingOutput outgoing(OutgoingGraphFragment result) {
        return new CallGraphMcpDtos.OutgoingOutput(result);
    }

    public CallGraphMcpDtos.IncomingOutput incoming(IncomingGraphFragment result) {
        return new CallGraphMcpDtos.IncomingOutput(result);
    }
}
