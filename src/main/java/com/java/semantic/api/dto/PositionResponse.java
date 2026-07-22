package com.java.semantic.api.dto;

import org.springframework.util.Assert;

/** Zero-based UTF-16 source coordinate. */
public record PositionResponse(int line, int character) {

    public PositionResponse {
        Assert.isTrue(line >= 0, "line must not be negative");
        Assert.isTrue(character >= 0, "character must not be negative");
    }
}
