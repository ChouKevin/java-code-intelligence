package com.java.semantic.mcp;

import org.springframework.util.Assert;

import java.util.function.Function;

/** 定義一個由 MCP provider 提供的無狀態查詢工具 */
public record McpQueryRegistration<I, O>(
        String name,
        String description,
        Class<I> inputType,
        Class<O> outputType,
        Function<I, O> handler) {

    public McpQueryRegistration {
        Assert.hasText(name, "name is required");
        Assert.hasText(description, "description is required");
        Assert.notNull(inputType, "inputType is required");
        Assert.notNull(outputType, "outputType is required");
        Assert.notNull(handler, "handler is required");
    }
}
