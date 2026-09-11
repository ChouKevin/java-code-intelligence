# MCP Agent 接受測試

這個檢查使用 repository 內的 payment、order、video 固定 fixture，先以真實 JDT LS 與正式 Indexer exporter 建立、驗證、封存並發布 Mongo generation，再以同一個暫時 Mongo 啟動真正的 Query HTTP/MCP 服務。測試只在執行期間啟動 Query，結束時會關閉它與暫時 Mongo；不會啟動 Indexer Spring application，也不會預先寫入 Query facts。

完整的測試入口與先決條件矩陣見 [Testing and verification](testing.md)。

## 前置條件與執行

- Java 21、Maven 3.9+、可用的 Docker，以及真實 JDT LS 安裝。
- `JDTLS_HOME` 指向 JDT LS 目錄，例如 `/opt/jdtls`。

```bash
JDTLS_HOME=/opt/jdtls scripts/test-indexer-query-contract.sh
```

這是 opt-in 的本機 fixture SDK journey，使用測試期間建立的暫時服務；它不是 `-Pdeployed-it` 的既有 Query deployment check，也不需要預先部署 Query。普通 `mvn --batch-mode --no-transfer-progress test` 不需要 Docker 或 JDT LS。

## fixture SDK journey 的證據

測試以 `X-Api-Token` 呼叫暫時 Query 的 `/mcp`，先檢查十二個工具與成功輸出 schema，再從回傳的 repository revision 和 fact ID 執行：

- payment `search_code` 到 `get_fact_source`，並驗證 `sourceCoverage`；
- video `POST /videos` route 到 handler、`VideoService.upload` callee、`DefaultVideoService.upload` implementation，最後取得原始碼；
- 空搜尋只表示目前已索引證據沒有匹配項，仍回傳 `sourceCoverage`；
- 過期 revision 回傳 `REVISION_OUTDATED/currentRevision`；以 current revision 重新搜尋並重新取得 fact ID 後才讀取 source。

`sourceCoverage.indexedSourceCount` 是本次授權且 package-filter 後的已索引 source row 數；`issueCount` 是 source issue entry 的總數，`issueCodes` 是排序後去重的原因碼。零 issue 不代表完整 repository coverage 或語意完整，也不能用來判定功能不存在。

測試輸出每段 journey 的 MCP tool call 數、structuredContent 重新序列化的 UTF-8 bytes（不含 MCP/HTTP envelope、initialize 與 tools/list）與 elapsed milliseconds（含 journey 處理、不含 index 啟動）。這些是觀察基線，沒有任意效能門檻；不會輸出 token 或 source body。

fixture revision 是測試用的固定 identity，不是 Git admission、dispatcher publication transition 或真實私有 repository 的驗證。SDK client contract check 也不會評估 LLM Agent；實際內部 Agent 與 repository 尚待選定。

## 實際 Agent 手動記錄

連接實際 Query 時，使用 `/mcp` 與 `X-Api-Token: <query-read-token>`。由 `list_repositories` 或 `get_repository` 複製 `repositoryId`/`revision`，由工具回應複製 fact ID；使用 `page.hasMore` 時繼續分頁。`search_code` 是 ASCII 英數 code-token prefix 搜尋，不是自然語言檢索；`find_api_routes` 和 event listener 都需要精確值。空結果不表示某項功能不存在，也不表示索引完整。

若回應 `REVISION_OUTDATED`，先採用 `currentRevision`。直接搜尋可用新 revision 重試；任何 fact-bound call 都必須重新搜尋或瀏覽以取得新 revision 的 fact ID，不能沿用舊 ID。

`find_callees` 每個項目都包含 `resolutionStatus`：內部 relation target 為 `INDEXED`，`ExternalTarget.UnresolvedCall` 為 `UNRESOLVED`，其他具型別的 external target 為 `UNINDEXED_TARGET`。這只描述目前索引中可觀察到的 target 形態；`UNINDEXED_TARGET` 不代表已確認 target 存在於外部系統，也不表示語意完整。

每個手動 Agent task 請保存以下無敏感內容的記錄：

| Task | repositoryId / revision | tool calls | response UTF-8 bytes | elapsed ms | returned fact/source evidence |
| --- | --- | ---: | ---: | ---: | --- |
| `<task>` | `<id> / <revision>` | `<count>` | `<bytes>` | `<ms>` | `<fact ID and path/line range>` |

不要把 token、Mongo URI、完整 source body 或私有 repository 內容寫入記錄。
