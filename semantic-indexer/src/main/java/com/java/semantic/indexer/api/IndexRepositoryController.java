package com.java.semantic.indexer.api;

import com.java.semantic.indexer.application.IndexerPreparationFacade;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/index/repositories/{repositoryId}")
public final class IndexRepositoryController {
    private final IndexerPreparationFacade facade;

    public IndexRepositoryController(IndexerPreparationFacade facade) {
        this.facade = facade;
    }

    @PostMapping("/source")
    public ResponseEntity<Map<String, Object>> prepare(@PathVariable String repositoryId,
            @RequestBody Map<String, Object> body) {
        Map<String, Object> result = facade.prepareSource(inputs(repositoryId, body));
        return ResponseEntity.accepted().location(URI.create("/index/repositories/" + result.get("repositoryId")
                + "/jobs?jobId=" + result.get("jobId"))).body(result);
    }

    @GetMapping("/jobs")
    public ResponseEntity<Map<String, Object>> job(@PathVariable String repositoryId,
            @RequestParam MultiValueMap<String, String> selectors) {
        Map<String, Object> fields = new LinkedHashMap<>();
        selectors.forEach((key, values) -> {
            if (values.size() != 1) {
                throw new IllegalArgumentException("job selector must occur exactly once");
            }
            fields.put(key, values.getFirst());
        });
        return ResponseEntity.ok(facade.getJob(inputs(repositoryId, fields)));
    }

    private static Map<String, Object> inputs(String repositoryId, Map<String, Object> body) {
        if (body == null) {
            throw new IllegalArgumentException("request body must be an object");
        }
        if (body.containsKey("repositoryId")) {
            throw new IllegalArgumentException("repositoryId belongs only in HTTP path");
        }
        Map<String, Object> fields = new LinkedHashMap<>(body);
        fields.put("repositoryId", repositoryId);
        return fields;
    }
}
