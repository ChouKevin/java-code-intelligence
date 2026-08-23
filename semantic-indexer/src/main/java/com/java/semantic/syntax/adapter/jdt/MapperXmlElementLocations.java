package com.java.semantic.syntax.adapter.jdt;

import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 直接從原始 XML 找出 mapper 直屬 element 的精確位置，不改寫內容 */
final class MapperXmlElementLocations {

    private MapperXmlElementLocations() {
    }

    static List<ElementLocation> directMapperChildren(String source) {
        String text = java.util.Objects.requireNonNull(source, "source is required");
        List<Node> completed = new ArrayList<>();
        ArrayDeque<Node> stack = new ArrayDeque<>();
        int index = 0;
        while (index < text.length()) {
            if (text.charAt(index) != '<') {
                index++;
                continue;
            }
            if (text.startsWith("<!--", index)) {
                index = after(text, index + 4, "-->");
                continue;
            }
            if (text.startsWith("<![CDATA[", index)) {
                index = after(text, index + 9, "]]>");
                continue;
            }
            if (text.startsWith("<?", index)) {
                index = after(text, index + 2, "?>");
                continue;
            }
            if (text.startsWith("<!", index)) {
                index = after(text, index + 2, ">");
                continue;
            }
            int tagEnd = tagEnd(text, index + 1);
            if (tagEnd < 0) {
                throw new IllegalArgumentException("mapper XML tag is incomplete");
            }
            if (index + 1 < text.length() && text.charAt(index + 1) == '/') {
                close(stack, completed, index, tagEnd + 1);
            } else {
                open(text, stack, completed, index, tagEnd + 1);
            }
            index = tagEnd + 1;
        }
        if (!stack.isEmpty()) {
            throw new IllegalArgumentException("mapper XML element is not closed");
        }
        List<ElementLocation> locations = new ArrayList<>();
        for (Node node : completed) {
            if (node.parentName().equals("mapper")) {
                locations.add(new ElementLocation(
                        node.name(), node.attributes(), syntaxRange(text, node.start(), node.end()), node.descendants()));
            }
        }
        return List.copyOf(locations);
    }

    private static void open(String source, ArrayDeque<Node> stack, List<Node> completed, int start, int end) {
        String tag = source.substring(start + 1, end - 1).trim();
        boolean selfClosing = tag.endsWith("/");
        String content = selfClosing ? tag.substring(0, tag.length() - 1).trim() : tag;
        int nameEnd = 0;
        while (nameEnd < content.length() && !Character.isWhitespace(content.charAt(nameEnd))) {
            nameEnd++;
        }
        String name = content.substring(0, nameEnd);
        Node node = new Node(name, attributes(content.substring(nameEnd)), start, stack.isEmpty() ? "" : stack.peek().name());
        if (selfClosing) {
            node.end(end);
            completed.add(node);
            if (!stack.isEmpty()) {
                stack.peek().addDescendant(node);
            }
            return;
        }
        stack.push(node);
    }

    private static void close(ArrayDeque<Node> stack, List<Node> completed, int start, int end) {
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("mapper XML close tag has no opening tag");
        }
        Node node = stack.pop();
        node.end(end);
        completed.add(node);
        if (!stack.isEmpty()) {
            stack.peek().addDescendant(node);
        }
    }

    private static int tagEnd(String source, int index) {
        char quote = 0;
        for (int cursor = index; cursor < source.length(); cursor++) {
            char current = source.charAt(cursor);
            if (quote != 0) {
                if (current == quote) {
                    quote = 0;
                }
            } else if (current == '\'' || current == '"') {
                quote = current;
            } else if (current == '>') {
                return cursor;
            }
        }
        return -1;
    }

    private static int after(String source, int index, String terminator) {
        int end = source.indexOf(terminator, index);
        if (end < 0) {
            throw new IllegalArgumentException("mapper XML section is incomplete");
        }
        return end + terminator.length();
    }

    private static Map<String, String> attributes(String source) {
        Map<String, String> attributes = new LinkedHashMap<>();
        int index = 0;
        while (index < source.length()) {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
                index++;
            }
            int nameStart = index;
            while (index < source.length() && !Character.isWhitespace(source.charAt(index)) && source.charAt(index) != '=') {
                index++;
            }
            if (nameStart == index) {
                break;
            }
            String name = source.substring(nameStart, index);
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
                index++;
            }
            if (index >= source.length() || source.charAt(index) != '=') {
                throw new IllegalArgumentException("mapper XML attribute has no value");
            }
            index++;
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
                index++;
            }
            if (index >= source.length() || (source.charAt(index) != '\'' && source.charAt(index) != '"')) {
                throw new IllegalArgumentException("mapper XML attribute is not quoted");
            }
            char quote = source.charAt(index++);
            int valueStart = index;
            while (index < source.length() && source.charAt(index) != quote) {
                index++;
            }
            if (index >= source.length()) {
                throw new IllegalArgumentException("mapper XML attribute is incomplete");
            }
            attributes.put(name, source.substring(valueStart, index));
            index++;
        }
        return Map.copyOf(attributes);
    }

    private static SyntaxRange syntaxRange(String source, int start, int end) {
        return new SyntaxRange(position(source, start), position(source, end));
    }

    private static SyntaxPosition position(String source, int offset) {
        int line = 0;
        int lineStart = 0;
        for (int index = 0; index < offset; index++) {
            char current = source.charAt(index);
            if (current == '\r') {
                line++;
                lineStart = index + 1;
            } else if (current == '\n') {
                if (index == 0 || source.charAt(index - 1) != '\r') {
                    line++;
                }
                lineStart = index + 1;
            }
        }
        return new SyntaxPosition(line, offset - lineStart);
    }

    record ElementLocation(String name, Map<String, String> attributes, SyntaxRange range, List<Node> descendants) {

        ElementLocation {
            attributes = Map.copyOf(attributes);
            descendants = List.copyOf(descendants);
        }

        List<String> descendantAttributeValues(String name, String attribute) {
            return descendants.stream()
                    .filter(descendant -> descendant.name().equals(name))
                    .map(Node::attributes)
                    .map(values -> values.get(attribute))
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }
    }

    static final class Node {

        private final String name;
        private final Map<String, String> attributes;
        private final int start;
        private final String parentName;
        private final List<Node> descendants = new ArrayList<>();
        private int end;

        private Node(String name, Map<String, String> attributes, int start, String parentName) {
            this.name = name;
            this.attributes = Map.copyOf(attributes);
            this.start = start;
            this.parentName = parentName;
        }

        String name() {
            return name;
        }

        Map<String, String> attributes() {
            return attributes;
        }

        int start() {
            return start;
        }

        String parentName() {
            return parentName;
        }

        List<Node> descendants() {
            return List.copyOf(descendants);
        }

        void end(int value) {
            end = value;
        }

        int end() {
            return end;
        }

        void addDescendant(Node descendant) {
            descendants.add(descendant);
            descendants.addAll(descendant.descendants());
        }
    }
}
