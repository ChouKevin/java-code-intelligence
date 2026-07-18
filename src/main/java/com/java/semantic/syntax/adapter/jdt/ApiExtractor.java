package com.java.semantic.syntax.adapter.jdt;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.java.semantic.syntax.domain.ApiEntryPoint;

import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/** 從型別宣告抽取 HTTP 端點 */
final class ApiExtractor {

    /** 方法層映射註解的優先序 */
    private static final List<String> MAPPING_ANNOTATIONS = List.of(
            "GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping", "RequestMapping");

    private static final String REQUEST_MAPPING = "RequestMapping";

    private static final String DEPRECATED = "Deprecated";

    /** Feign 是出站呼叫，帶著 @GetMapping 也不是入站端點 */
    private static final String FEIGN_CLIENT = "FeignClient";

    /** value 與 path 在 Spring 中互為別名，這裡宣告一個確定的優先序 */
    private static final String[] PATH_ATTRIBUTES = {"value", "path"};

    private static final String METHOD_ATTRIBUTE = "method";

    private static final String MAPPING_SUFFIX = "Mapping";

    /** Swagger 2 與 OpenAPI 3 的描述屬性，依原始碼順序全數收集 */
    private static final List<SwaggerSource> SWAGGER_SOURCES = List.of(
            new SwaggerSource("ApiOperation", List.of("value", "notes")),
            new SwaggerSource("Operation", List.of("summary", "description")));

    private record SwaggerSource(String annotation, List<String> attributes) {
    }

    private ApiExtractor() {
    }

    /** 類別層 @RequestMapping 的路徑，沒有宣告時為空 */
    static List<String> basePathsOf(AbstractTypeDeclaration type) {
        if (isExcluded(type)) {
            return List.of();
        }
        return AnnotationReader.find(type, REQUEST_MAPPING)
                .map(mapping -> AnnotationReader.stringValues(mapping, PATH_ATTRIBUTES))
                .orElseGet(List::of);
    }

    /** 型別的所有 HTTP 端點，路徑已與類別層路徑組合 */
    static List<ApiEntryPoint> extract(AbstractTypeDeclaration type) {
        if (isExcluded(type)) {
            return List.of();
        }

        List<String> basePaths = basePathsOf(type);
        List<String> effectiveBases = CollectionUtils.isEmpty(basePaths) ? List.of("") : basePaths;

        List<ApiEntryPoint> entryPoints = new ArrayList<>();
        for (MethodDeclaration method : SourceTypes.declaredMethodsOf(type)) {
            if (AnnotationReader.isPresent(method, DEPRECATED)) {
                continue;
            }
            Optional<Annotation> mapping = AnnotationReader.findAny(method, MAPPING_ANNOTATIONS);
            if (mapping.isEmpty()) {
                continue;
            }
            entryPoints.addAll(toEntryPoints(method, mapping.get(), effectiveBases));
        }
        return List.copyOf(entryPoints);
    }

    private static List<ApiEntryPoint> toEntryPoints(MethodDeclaration method, Annotation mapping,
            List<String> basePaths) {
        List<String> methodPaths = AnnotationReader.stringValues(mapping, PATH_ATTRIBUTES);
        List<String> effectiveMethodPaths = CollectionUtils.isEmpty(methodPaths) ? List.of("") : methodPaths;

        List<String> verbs = verbsOf(mapping);
        List<String> swagger = swaggerDescriptionsOf(method);
        String description = JavadocReader.descriptionOf(method);
        String name = method.getName().getIdentifier();

        List<ApiEntryPoint> entryPoints = new ArrayList<>();
        for (String basePath : basePaths) {
            for (String methodPath : effectiveMethodPaths) {
                entryPoints.add(new ApiEntryPoint(
                        name, description, PathCombiner.combine(basePath, methodPath), verbs, swagger));
            }
        }
        return entryPoints;
    }

    private static List<String> verbsOf(Annotation mapping) {
        String annotationName = AnnotationReader.simpleNameOf(mapping);
        if (!REQUEST_MAPPING.equals(annotationName)) {
            return List.of(annotationName.replace(MAPPING_SUFFIX, "").toUpperCase());
        }
        List<String> declared = AnnotationReader.enumConstantNames(mapping, METHOD_ATTRIBUTE);
        return CollectionUtils.isEmpty(declared) ? List.of(ApiEntryPoint.ALL_METHODS) : declared;
    }

    /** LinkedHashSet 保留原始碼順序，舊分析器用 HashSet 導致輸出順序不定 */
    private static List<String> swaggerDescriptionsOf(MethodDeclaration method) {
        Set<String> descriptions = new LinkedHashSet<>();
        for (SwaggerSource source : SWAGGER_SOURCES) {
            AnnotationReader.find(method, source.annotation()).ifPresent(annotation -> {
                for (String attribute : source.attributes()) {
                    descriptions.addAll(AnnotationReader.stringValues(annotation, attribute));
                }
            });
        }
        return List.copyOf(descriptions);
    }

    private static boolean isExcluded(AbstractTypeDeclaration type) {
        return !SourceTypes.isEntryPointCandidate(type) || AnnotationReader.isPresent(type, FEIGN_CLIENT);
    }

    /** 是否為整份檔案都該跳過的 advice 型別 */
    static boolean isControllerAdvice(AbstractTypeDeclaration type) {
        return AnnotationReader.isAnyPresent(type, List.of("RestControllerAdvice", "ControllerAdvice"));
    }

    /** 路徑組合，兩段皆空時回傳 "/" */
    static final class PathCombiner {

        private PathCombiner() {
        }

        static String combine(String base, String relative) {
            String head = base.trim();
            String tail = relative.trim();
            if (!StringUtils.hasText(head)) {
                return withLeadingSlash(tail);
            }
            if (!StringUtils.hasText(tail)) {
                return withLeadingSlash(head);
            }
            String normalizedHead = withLeadingSlash(head);
            if (normalizedHead.endsWith("/")) {
                normalizedHead = normalizedHead.substring(0, normalizedHead.length() - 1);
            }
            return normalizedHead + withLeadingSlash(tail);
        }

        private static String withLeadingSlash(String value) {
            return value.startsWith("/") ? value : "/" + value;
        }
    }
}
