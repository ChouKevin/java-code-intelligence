package com.java.semantic.trie;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 以相同流程正規化儲存路由模板與傳入的 API 路徑 */
public final class ApiPathNormalizer {

    static final String ONE_SEGMENT_WILDCARD = "{*}";
    static final String REST_WILDCARD = "{**}";

    private static final Set<String> HTTP_METHODS = Set.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD", "TRACE", "ALL");
    private static final Pattern METHOD_PREFIX = Pattern.compile(
            "(?i)^\\[?(GET|POST|PUT|PATCH|DELETE|OPTIONS|HEAD|TRACE)\\]?\\s*:?\\s*"
                    + "(/\\S*|[a-z][a-z0-9+.-]*:\\S*)$");
    private static final Pattern ABSOLUTE_URI = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:.*$");

    private ApiPathNormalizer() {
    }

    public static NormalizedApiPath normalize(String apiPath, String httpMethod) {
        Assert.notNull(apiPath, "apiPath is required");
        String unquoted = stripWrappingQuotes(apiPath.trim());
        MethodAndPath prefixed = extractMethodPrefix(unquoted);
        String argumentMethod = normalizeMethod(httpMethod);
        if (StringUtils.hasText(prefixed.httpMethod())
                && StringUtils.hasText(argumentMethod)
                && !prefixed.httpMethod().equals(argumentMethod)) {
            throw new IllegalArgumentException("Conflicting HTTP methods");
        }
        String method = StringUtils.hasText(prefixed.httpMethod()) ? prefixed.httpMethod() : argumentMethod;
        String route = extractRoutePath(prefixed.path());
        route = decodeSafeCharacters(route).replaceAll("/{2,}", "/");
        route = StringUtils.hasText(route) ? route : "/";
        route = route.startsWith("/") ? route : "/" + route;
        if (route.length() > 1 && route.endsWith("/")) {
            route = route.substring(0, route.length() - 1);
        }
        return new NormalizedApiPath(canonicalizeSegments(route), method);
    }

    private static String normalizeMethod(String httpMethod) {
        if (!StringUtils.hasText(httpMethod)) {
            return "";
        }
        String normalized = httpMethod.trim().toUpperCase(Locale.ROOT);
        if (!HTTP_METHODS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported HTTP method: " + httpMethod);
        }
        return normalized;
    }

    private static MethodAndPath extractMethodPrefix(String value) {
        Matcher matcher = METHOD_PREFIX.matcher(value);
        if (!matcher.matches()) {
            return new MethodAndPath("", value);
        }
        return new MethodAndPath(normalizeMethod(matcher.group(1)), matcher.group(2));
    }

    private static String extractRoutePath(String value) {
        if (ABSOLUTE_URI.matcher(value).matches()) {
            return extractAbsolutePath(value);
        }
        int queryIndex = value.indexOf('?');
        int fragmentIndex = value.indexOf('#');
        int end = value.length();
        if (queryIndex >= 0) {
            end = Math.min(end, queryIndex);
        }
        if (fragmentIndex >= 0) {
            end = Math.min(end, fragmentIndex);
        }
        return value.substring(0, end);
    }

    private static String extractAbsolutePath(String value) {
        validatePercentEscapes(value);
        String escaped = value.replace("%", "%25")
                .replace("{", "%7B")
                .replace("}", "%7D")
                .replace(" ", "%20");
        URI uri = URI.create(escaped);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!Set.of("http", "https").contains(scheme)) {
            throw new IllegalArgumentException("Unsupported absolute URL scheme: " + scheme);
        }
        if (uri.isOpaque()
                || !StringUtils.hasText(uri.getRawAuthority())
                || !StringUtils.hasText(uri.getHost())) {
            throw new IllegalArgumentException("Malformed absolute URL");
        }
        String rawPath = uri.getRawPath();
        String path = StringUtils.hasText(rawPath) ? rawPath : "";
        return path.replace("%7B", "{")
                .replace("%7D", "}")
                .replace("%20", " ")
                .replace("%25", "%");
    }

    private static void validatePercentEscapes(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '%'
                    && (index + 2 >= value.length()
                    || !isHex(value.charAt(index + 1))
                    || !isHex(value.charAt(index + 2)))) {
                throw new IllegalArgumentException("Malformed percent escape in absolute URL");
            }
        }
    }

    private static String decodeSafeCharacters(String value) {
        StringBuilder result = new StringBuilder(value.length());
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '%' && index + 2 < value.length()
                    && isHex(value.charAt(index + 1)) && isHex(value.charAt(index + 2))) {
                int decoded = Integer.parseInt(value.substring(index + 1, index + 3), 16);
                char decodedCharacter = (char) decoded;
                if (isSafeToDecode(decodedCharacter)) {
                    result.append(decodedCharacter);
                } else {
                    result.append('%')
                            .append(Character.toUpperCase(value.charAt(index + 1)))
                            .append(Character.toUpperCase(value.charAt(index + 2)));
                }
                index += 3;
            } else {
                result.append(current);
                index++;
            }
        }
        return result.toString();
    }

    private static boolean isSafeToDecode(char value) {
        return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9'
                || value == '-' || value == '.' || value == '_' || value == '~';
    }

    private static boolean isHex(char value) {
        return value >= '0' && value <= '9'
                || value >= 'a' && value <= 'f'
                || value >= 'A' && value <= 'F';
    }

    private static String canonicalizeSegments(String path) {
        String[] segments = path.split("/", -1);
        StringBuilder result = new StringBuilder(path.length());
        for (int index = 1; index < segments.length; index++) {
            result.append('/').append(canonicalizeSegment(segments[index]));
        }
        return StringUtils.hasText(result) ? result.toString() : "/";
    }

    private static String canonicalizeSegment(String segment) {
        if ("**".equals(segment)
                || segment.startsWith("{*") && segment.endsWith("}") && segment.length() > 3) {
            return REST_WILDCARD;
        }
        if (segment.startsWith("{{") && segment.endsWith("}}")
                || segment.startsWith("{") && segment.endsWith("}")
                || segment.startsWith(":") && segment.length() > 1
                || segment.startsWith("<") && segment.endsWith(">")) {
            return ONE_SEGMENT_WILDCARD;
        }
        return segment;
    }

    private static String stripWrappingQuotes(String value) {
        if (value.length() >= 2
                && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private record MethodAndPath(String httpMethod, String path) {
    }
}
