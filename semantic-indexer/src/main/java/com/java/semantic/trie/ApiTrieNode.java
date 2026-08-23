package com.java.semantic.trie;

import java.util.HashMap;
import java.util.Map;

final class ApiTrieNode {

    static final String WILDCARD = ApiPathNormalizer.ONE_SEGMENT_WILDCARD;
    static final String REST_WILDCARD = ApiPathNormalizer.REST_WILDCARD;
    static final String METHOD_ALL = "ALL";

    final Map<String, ApiTrieNode> children = new HashMap<>();
    final Map<String, Map<String, ApiEntryPointRef>> methodMap = new HashMap<>();
}
