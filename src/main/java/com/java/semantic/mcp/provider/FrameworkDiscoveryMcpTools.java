package com.java.semantic.mcp.provider;

import com.java.semantic.mcp.McpQueryProvider;
import com.java.semantic.mcp.McpQueryRegistration;
import com.java.semantic.mcp.dto.framework.FrameworkDiscoveryMcpDtos;
import com.java.semantic.mcp.mapper.FrameworkDiscoveryMcpMapper;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.semantic.application.MethodImplementationDiscoveryApplicationService;
import com.java.semantic.semantic.application.MethodImplementationDiscoveryQuery;
import com.java.semantic.syntax.application.EventListenerDiscoveryApplicationService;
import com.java.semantic.syntax.application.EventListenerDiscoveryQuery;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** 發布 framework event 與 implementation discovery MCP 查詢 */
@Component
public final class FrameworkDiscoveryMcpTools implements McpQueryProvider {

    private final EventListenerDiscoveryApplicationService eventListenerDiscoveryApplicationService;
    private final MethodImplementationDiscoveryApplicationService methodImplementationDiscoveryApplicationService;
    private final FrameworkDiscoveryMcpMapper mapper;

    public FrameworkDiscoveryMcpTools(
            EventListenerDiscoveryApplicationService eventListenerDiscoveryApplicationService,
            MethodImplementationDiscoveryApplicationService methodImplementationDiscoveryApplicationService,
            FrameworkDiscoveryMcpMapper mapper) {
        this.eventListenerDiscoveryApplicationService = Objects.requireNonNull(
                eventListenerDiscoveryApplicationService, "eventListenerDiscoveryApplicationService is required");
        this.methodImplementationDiscoveryApplicationService = Objects.requireNonNull(
                methodImplementationDiscoveryApplicationService, "methodImplementationDiscoveryApplicationService is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
    }

    @Override
    public List<McpQueryRegistration<?, ?>> registrations() {
        return List.of(
                new McpQueryRegistration<>(
                        "semantic_discover_event_listeners",
                        "Discover bounded event listener candidates at one repository revision",
                        FrameworkDiscoveryMcpDtos.EventListenersInput.class,
                        FrameworkDiscoveryMcpDtos.EventListenersOutput.class,
                        this::eventListeners),
                new McpQueryRegistration<>(
                        "semantic_discover_method_implementations",
                        "Discover implementations for one canonical method declaration",
                        FrameworkDiscoveryMcpDtos.MethodImplementationsInput.class,
                        FrameworkDiscoveryMcpDtos.MethodImplementationsOutput.class,
                        this::methodImplementations));
    }

    private FrameworkDiscoveryMcpDtos.EventListenersOutput eventListeners(
            FrameworkDiscoveryMcpDtos.EventListenersInput input) {
        return mapper.eventListeners(eventListenerDiscoveryApplicationService.discover(new EventListenerDiscoveryQuery(
                RepositoryId.of(input.repoId()),
                new RepositoryRevision(input.expectedRevision()),
                input.eventType(),
                input.offset(),
                input.limit())));
    }

    private FrameworkDiscoveryMcpDtos.MethodImplementationsOutput methodImplementations(
            FrameworkDiscoveryMcpDtos.MethodImplementationsInput input) {
        return mapper.methodImplementations(methodImplementationDiscoveryApplicationService.discover(
                new MethodImplementationDiscoveryQuery(
                        RepositoryId.of(input.repoId()),
                        new RepositoryRevision(input.expectedRevision()),
                        input.declarationTarget())));
    }
}
