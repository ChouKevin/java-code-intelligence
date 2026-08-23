package com.java.semantic.syntax.domain;

import java.util.List;
import java.util.Objects;

/**
 * HTTP 端點
 * <p>
 * 一條路由一筆：@GetMapping({"/a","/b"}) 會展開成兩筆，
 * 舊分析器把它併成 apiUrl = "/base/a,/b" 這種對不上任何真實路由的字串
 *
 * @param name                方法名稱
 * @param description         方法 Javadoc
 * @param apiUrl              類別層與方法層路徑組合後的單一路由
 * @param httpMethods         此路由接受的動詞，@RequestMapping 未指定 method 時為 ALL
 * @param swaggerDescriptions Swagger 2 @ApiOperation 與 OpenAPI 3 @Operation 的描述，依原始碼順序
 */
public record ApiEntryPoint(
        String name,
        String description,
        String apiUrl,
        List<String> httpMethods,
        List<String> swaggerDescriptions,
        MethodTargetResolution analysisTarget) implements EntryPointMethod {

    /** 未指定動詞時的萬用值 */
    public static final String ALL_METHODS = "ALL";

    public ApiEntryPoint {
        httpMethods = List.copyOf(httpMethods);
        swaggerDescriptions = List.copyOf(swaggerDescriptions);
        analysisTarget = Objects.requireNonNull(analysisTarget, "analysisTarget is required");
    }

    @Override
    public EntryPointType type() {
        return EntryPointType.API;
    }
}
