package com.java.semantic.api;

import com.java.semantic.query.application.SemanticQueryFacade;
import com.java.semantic.query.application.SemanticQueryInput;
import java.util.Map;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP decoding only; strict source application binding is shared with MCP. */
@RestController
@RequestMapping("/api/v1")
public final class SemanticQueryController {

    private final SemanticQueryFacade facade;

    public SemanticQueryController(SemanticQueryFacade facade) {
        this.facade = Objects.requireNonNull(facade, "source query facade");
    }

    @GetMapping("/repositories")
    public Object listRepositories(@RequestParam Map<String, String> input) {
        return facade.execute("list_repositories", SemanticQueryInput.repositoryQuery(input));
    }

    @PostMapping("/context")
    public Object getContext(@RequestBody Map<String, Object> input) {
        return facade.execute("get_context", input);
    }

    @PostMapping("/files")
    public Object listFiles(@RequestBody Map<String, Object> input) {
        return facade.execute("list_files", input);
    }

    @PostMapping("/search-text")
    public Object searchText(@RequestBody Map<String, Object> input) {
        return facade.execute("search_text", input);
    }

    @PostMapping("/source")
    public Object readSource(@RequestBody Map<String, Object> input) {
        return facade.execute("read_source", input);
    }
}
