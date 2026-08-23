package com.java.semantic.api;

import com.java.semantic.api.dto.DiscoverEventListenersRequest;
import com.java.semantic.api.dto.DiscoverEventListenersResponse;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.syntax.application.EventListenerDiscoveryApplicationService;
import com.java.semantic.syntax.application.EventListenerDiscoveryQuery;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** 事件監聽器探索的無狀態 HTTP 邊界 */
@RestController
@RequestMapping("/v1/discovery")
public final class EventListenerDiscoveryController {

    private final EventListenerDiscoveryApplicationService service;
    private final EventListenerDiscoveryResponseMapper mapper;

    public EventListenerDiscoveryController(
            EventListenerDiscoveryApplicationService service,
            EventListenerDiscoveryResponseMapper mapper) {
        this.service = Objects.requireNonNull(service, "service is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
    }

    @PostMapping("/event-listeners")
    public DiscoverEventListenersResponse discover(@Valid @RequestBody DiscoverEventListenersRequest request) {
        EventListenerDiscoveryQuery query = new EventListenerDiscoveryQuery(
                RepositoryId.of(request.repoId()),
                new RepositoryRevision(request.expectedRevision()),
                request.eventType(),
                request.offset(),
                request.limit());
        return mapper.toResponse(service.discover(query));
    }
}
