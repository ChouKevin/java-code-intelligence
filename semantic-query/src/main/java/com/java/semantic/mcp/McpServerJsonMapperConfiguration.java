package com.java.semantic.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Keeps Spring AI's MCP wire mapper aligned with the application response omission contract. */
@Configuration(proxyBeanMethods = false)
public class McpServerJsonMapperConfiguration {

    @Bean(name = "mcpServerJsonMapper")
    public JsonMapper mcpServerJsonMapper() {
        return new McpServerJsonMapperAutoConfiguration().mcpServerJsonMapper().rebuild()
                .changeDefaultPropertyInclusion(current -> JsonInclude.Value.construct(JsonInclude.Include.NON_ABSENT,
                        JsonInclude.Include.NON_ABSENT))
                .build();
    }
}
