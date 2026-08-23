package com.java.semantic.mcp.mapper;

import com.java.semantic.mcp.dto.framework.FrameworkDiscoveryMcpDtos;
import com.java.semantic.semantic.application.RevisionBoundMethodImplementations;
import com.java.semantic.syntax.application.RevisionBoundEventListenerDiscovery;
import org.springframework.stereotype.Component;

/** 將 framework discovery 結果投影為 MCP transport DTO */
@Component
public final class FrameworkDiscoveryMcpMapper {

    public FrameworkDiscoveryMcpDtos.EventListenersOutput eventListeners(RevisionBoundEventListenerDiscovery result) {
        return new FrameworkDiscoveryMcpDtos.EventListenersOutput(result);
    }

    public FrameworkDiscoveryMcpDtos.MethodImplementationsOutput methodImplementations(
            RevisionBoundMethodImplementations result) {
        return new FrameworkDiscoveryMcpDtos.MethodImplementationsOutput(result);
    }
}
