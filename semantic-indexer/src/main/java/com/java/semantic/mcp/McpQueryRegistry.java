package com.java.semantic.mcp;

import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 在 MCP server 啟動前驗證並固定查詢工具目錄 */
public final class McpQueryRegistry {

    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("[a-z][a-z0-9_]*");

    private static final List<String> CANONICAL_TOOL_NAMES = List.of(
            "semantic_analyze_incoming_call_graph",
            "semantic_analyze_outgoing_call_graph",
            "semantic_discover_concepts",
            "semantic_discover_event_listeners",
            "semantic_discover_method_implementations",
            "semantic_discover_type_members",
            "semantic_find_internal_references",
            "semantic_get_evidence_source",
            "semantic_get_method_source",
            "semantic_get_repository",
            "semantic_get_source_segment",
            "semantic_list_entry_points",
            "semantic_list_repositories",
            "semantic_lookup_api_routes",
            "semantic_resolve_concept",
            "semantic_resolve_source_symbol",
            "semantic_suggest_api_routes");

    private final List<McpQueryRegistration<?, ?>> registrations;

    public McpQueryRegistry(List<McpQueryProvider> providers) {
        Assert.notNull(providers, "providers are required");
        List<McpQueryRegistration<?, ?>> orderedRegistrations = new ArrayList<>();
        for (McpQueryProvider provider : providers) {
            orderedRegistrations.addAll(provider.registrations());
        }
        orderedRegistrations.sort(Comparator.comparing(McpQueryRegistration::name));
        validate(orderedRegistrations);
        this.registrations = List.copyOf(orderedRegistrations);
    }

    public static List<String> canonicalToolNames() {
        return CANONICAL_TOOL_NAMES;
    }

    public List<McpQueryRegistration<?, ?>> registrations() {
        return registrations;
    }

    private static void validate(List<McpQueryRegistration<?, ?>> registrations) {
        Set<String> names = new HashSet<>();
        for (McpQueryRegistration<?, ?> registration : registrations) {
            String name = registration.name();
            if (!TOOL_NAME_PATTERN.matcher(name).matches()) {
                throw new IllegalArgumentException("invalid MCP tool name: " + name);
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("duplicate MCP tool name: " + name);
            }
        }
        if (!names.equals(Set.copyOf(CANONICAL_TOOL_NAMES))) {
            throw new IllegalArgumentException("MCP tool catalog differs from the canonical allowlist");
        }
    }
}
