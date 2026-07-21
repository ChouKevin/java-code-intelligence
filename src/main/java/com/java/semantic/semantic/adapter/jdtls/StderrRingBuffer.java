package com.java.semantic.semantic.adapter.jdtls;

import org.springframework.util.Assert;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * 保存 JDT Language Server stderr 最後數行的安全分類
 *
 * 原始 stderr 可能包含儲存庫路徑、符號與原始碼,因此只保留有界分類與行數
 */
public final class StderrRingBuffer {

    private static final String ENTRY_PREFIX = "!ENTRY";
    private static final String MESSAGE_PREFIX = "!MESSAGE";
    private static final String STACK_PREFIX = "!STACK";
    private static final String EXCEPTION_TOKEN = "exception";

    private final int capacity;
    private final Deque<String> lines = new ArrayDeque<>();

    public StderrRingBuffer(int capacity) {
        Assert.isTrue(capacity > 0, "capacity must be positive");
        this.capacity = capacity;
    }

    /** 加入一行安全分類,超出容量時淘汰最舊的一行 */
    public synchronized String add(String line) {
        Assert.notNull(line, "line is required");
        if (lines.size() == capacity) {
            lines.removeFirst();
        }
        String safeLine = "stderr-category=" + categoryOf(line);
        lines.addLast(safeLine);
        return safeLine;
    }

    /** 回傳目前保留的安全分類,最舊在前 */
    public synchronized List<String> lines() {
        return List.copyOf(lines);
    }

    /** 以換行串接保留的安全分類,供失敗診斷輸出 */
    public synchronized String asText() {
        return String.join(System.lineSeparator(), lines);
    }

    private String categoryOf(String line) {
        if (line.startsWith(ENTRY_PREFIX)) {
            return "ENTRY";
        }
        if (line.startsWith(MESSAGE_PREFIX)) {
            return "MESSAGE";
        }
        if (line.startsWith(STACK_PREFIX)) {
            return "STACK";
        }
        if (line.toLowerCase(Locale.ROOT).contains(EXCEPTION_TOKEN)) {
            return "EXCEPTION";
        }
        if (line.isBlank()) {
            return "EMPTY";
        }
        return "OTHER";
    }
}
