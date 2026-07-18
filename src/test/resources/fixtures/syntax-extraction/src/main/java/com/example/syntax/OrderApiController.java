package com.example.syntax;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * 訂單 API
 *
 * @author fixture
 */
@RestController
@RequestMapping(RouteConstants.BASE)
public class OrderApiController {

    /** 取得單筆訂單 */
    @GetMapping("/{id}")
    public String get(Long id) {
        return "";
    }

    /** PATCH 端點，舊實作完全沒有掃描 */
    @PatchMapping("/{id}")
    public String patch(Long id) {
        return "";
    }

    /** 多路徑映射，應展開為多筆 */
    @PostMapping({"/a", "/b"})
    public String multi() {
        return "";
    }

    /** 兩個屬性都寫，且 path 寫在前面，用來證明優先序不是原始碼順序 */
    @RequestMapping(path = "/from-path", value = "/from-value")
    public String bothAttributes() {
        return "";
    }

    /** method 陣列，應解析為兩個動詞 */
    @RequestMapping(value = "/verbs", method = {RequestMethod.GET, RequestMethod.POST})
    public String verbs() {
        return "";
    }

    /** 常量摺疊後應為 /a/b */
    @GetMapping(RouteConstants.FOLDED)
    public String folded() {
        return "";
    }

    /** OpenAPI 3 註解，舊實作只認 Swagger 2 */
    @Operation(summary = "刪除訂單", description = "依 id 刪除")
    @DeleteMapping("/{id}")
    public String remove(Long id) {
        return "";
    }

    /** 標記為棄用，不應被掃描 */
    @Deprecated
    @GetMapping("/legacy")
    public String legacy() {
        return "";
    }

    /** 巢狀類別，其方法不應被外層重複計算 */
    @RestController
    @RequestMapping("/nested")
    public static class NestedController {

        @GetMapping("/inner")
        public String inner() {
            return "";
        }
    }
}
