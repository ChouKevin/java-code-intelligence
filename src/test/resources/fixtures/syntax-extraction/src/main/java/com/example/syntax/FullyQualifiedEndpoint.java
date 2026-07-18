package com.example.syntax;

/**
 * annotation 全部以完整名稱書寫
 * <p>
 * 掃描比對只看最後一段，因此仍須命中；metadata 則必須保留寫出的長格式
 * 兩套制度在這個 fixture 之前沒有任何測試分辨得出來
 */
@org.springframework.stereotype.Service
@org.springframework.web.bind.annotation.RequestMapping("/fqn")
public class FullyQualifiedEndpoint {

    /** 完整名稱書寫的 @GetMapping，掃描仍須認得 */
    @org.springframework.web.bind.annotation.GetMapping("/ping")
    public String ping() {
        return "";
    }
}
