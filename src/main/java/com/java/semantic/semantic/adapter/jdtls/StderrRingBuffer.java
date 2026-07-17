package com.java.semantic.semantic.adapter.jdtls;

import org.springframework.util.Assert;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 保存 JDT Language Server stderr 最後數行的環狀緩衝
 *
 * 工作區無法就緒時,stderr 是唯一能說明原因的通道
 * JDT LS 以 log.level=ALL 啟動,輸出量大;無上限保留等同於在 1 GB RSS 的程序旁再開一個記憶體洩漏
 */
public final class StderrRingBuffer {

    /** 單行上限,超過即截斷;堆疊追蹤中的單行可能極長 */
    static final int MAX_LINE_LENGTH = 512;

    private static final String TRUNCATION_MARK = "...";

    private final int capacity;
    private final Deque<String> lines = new ArrayDeque<>();

    public StderrRingBuffer(int capacity) {
        Assert.isTrue(capacity > 0, "capacity must be positive");
        this.capacity = capacity;
    }

    /** 加入一行,超出容量時淘汰最舊的一行 */
    public synchronized void add(String line) {
        Assert.notNull(line, "line is required");
        if (lines.size() == capacity) {
            lines.removeFirst();
        }
        lines.addLast(truncate(line));
    }

    /** 回傳目前保留的行,最舊在前 */
    public synchronized List<String> lines() {
        return List.copyOf(lines);
    }

    /** 以換行串接保留的行,供失敗診斷輸出 */
    public synchronized String asText() {
        return String.join(System.lineSeparator(), lines);
    }

    private String truncate(String line) {
        if (line.length() <= MAX_LINE_LENGTH) {
            return line;
        }
        return line.substring(0, MAX_LINE_LENGTH - TRUNCATION_MARK.length()) + TRUNCATION_MARK;
    }
}
