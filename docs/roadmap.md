# Java Code Intelligence — Source-first Repo Intelligence MCP

## 狀態與重建決策

本文件涵蓋 Source-first 重建 roadmap。Phase 1 runtime 已實作無 Mongo／JDT 的 source cutover；實際命令、證據與驗收限制見 [測試與驗收](operations/testing.md)。Phase 2 本次僅新增最小 revision retention／讀取保護／回收 log，最終驗收仍須通過對應 gates；其餘多 repo 管理與 Phase 3–6 仍是規劃。本次不清除既有服務資料。

採用 **clean cutover + 選擇性重用**：不遷移舊 Mongo generations、source snapshots、review records、job records 或舊 client contexts；保留有價值的 transport、security、identity 與 Git 安全處理。Git history 留存舊實作，不為未來可能重用而在新 runtime 維持停用的舊系統。

第一個 milestone：**沒有 MongoDB、沒有 JDT LS，Agent 仍能從固定 Git revision 搜尋、瀏覽、讀取 source，並引用實際 code evidence。**

## 不變原則

1. 證據優先序：指定 Git commit 的 code > semantic/symbol index > guide/README/docs。
2. Source tools 必須接受並回傳同一 `repositoryId + revision`；branch 更新不替換已取得的 context。
3. Source readiness 與 semantic readiness 分離；semantic 失敗不能使 source 失效。
4. Query 只讀取已發布 source，不 fetch、checkout、啟動 Indexer 或執行 repository code。
5. Indexer 持有 Git credentials、準備與發布 source；維持單一程序、單一 dispatcher。
6. HTTP／MCP 共用 facade、輸入驗證、結果與錯誤契約；讀取 token 與準備 token 分離。
7. `semantic-model` 只依賴 JDK；不增加 Spring、Mongo、JGit、parser 或 MCP 依賴。
8. Source response 有 path、line、bytes、筆數與時間上限；所有路徑均受 containment 與 repository authorization 約束。
9. Guide 是 `NOT_VERIFIED` navigation hint，不是 semantic fact，也不是工具執行指令。
10. Phase 1 不增加 database、parser、LLM、embedding、vector store 或 semantic fallback。

## 目標部署與模組

```text
Private admin MCP / HTTP                 Read-only MCP / HTTP
          |                                      |
  semantic-indexer                        semantic-query
  registration / Git / jobs               security / facade
  exact revision preparation              RepositorySourcePort
  atomic publication                      LocalRepositorySourceService
          |                                      |
          +-- published immutable source --------+
                  writer mount             read-only mount

                    semantic-model
              repository / revision / source contracts
```

- 保留三個 Maven module 名稱，不同時做 rename。
- Query 新增唯讀 filesystem／ripgrep source adapter；不引入 JGit。
- Indexer 使用既有 JGit 技術與安全驗證，從 exact commit 匯出 immutable revision directory。它不是會被 `reset --hard` 重用的 mutable checkout。
- 私有 Git object database、jobs、staging 與公開 source volume 分開；Query 不掛載 credentials、`.git` 或 staging。
- Source registry／revision manifest／current pointer 使用小型檔案 metadata，不使用 Mongo。
- Phase 3 之後才評估 SQLite；SQLite 只保存可重建 index。

## Phase 1 — Source-first Repo MCP

### 交付

Query 僅公開五個 tools：

| Tool | 新責任 |
| --- | --- |
| `list_repositories` | 顯示已註冊且授權的 repo；READY item 帶目前 revision，未準備者不捏造 SHA |
| `get_context` | 讀取已發布 current 或指定已準備 revision，不觸發 fetch／prepare |
| `list_files` | 固定 revision 的 direct-child navigation，穩定分頁，不回傳整棵大型 tree |
| `search_text` | 固定 revision 的 literal ripgrep search，含 bounded results、timeout、truncation disclosure |
| `read_source` | 固定 revision 的檔案／行範圍讀取，最多 500 行、預設最多 64 KiB content，支援無缺漏續讀 |

Private Indexer 提供 `prepare_source`、`get_job`。準備與原始 requestId recovery 保留非同步與單一 dispatcher 模式，但移除 Mongo job store／semantic publication 依賴。`prepare_semantic_index` 尚未提供，不放空殼 endpoint。

### 順序與依赖

1. 固定新 source contract、filesystem publication format、安全政策與 architecture rules。
2. 完成 source-only registration／durable job／Git revision preparation／atomic publication。
3. 完成 Query revision admission、filesystem list/read、ripgrep search。
4. 五個 tools 與 HTTP 一起切換；移除舊 semantic/review routes、schema、boot wiring 與 dependencies。
5. 在全新資料目錄完成真實 MCP／HTTP journey，更新 deployment、image、CI、操作文件後發行。

2 與 3 可在共同契約固定後分開實作；4 依賴兩者。工作包不是獨立 release；只有通過整個 Phase 1 的成品才可部署，不能發行缺少 prepare 或 source tools 的 scaffold。

### 驗收門檻

- Mongo 不安裝、不連線；Indexer／Query distribution 不含 Mongo runtime dependency。
- JDT LS 不安裝、不啟動；Phase 1 distribution 不含 JDT/LSP4J runtime dependency。
- 使用真實 local Git remote 與真實 ripgrep，不以 mock 搜尋結果代替。
- Prepare main 至 A，透過 MCP 完成五個 tools；repository-scoped responses 與 READY repository item 明確帶 A。
- 發布 B 後，舊 A context 仍可讀 A；不回 `REVISION_OUTDATED` 要求改讀 B。
- 停止 Indexer／遠端 Git 後，重新啟動 Query 仍可讀已發布 A／B。
- traversal、absolute path、symlink、跨 repo、未授權、偽造 cursor、timeout 均有受控結果。
- 從 fixture 的 controller → service → repository／client 整理一條真實流程，每個步驟附 repo、SHA、path、line；流程結論由外部 Agent 產生，不由 server 生成。

### 明確不做

Semantic search、outline、entry points、relations、review/diff/history tools、SQLite、parser、JDT precision resolver、cross-repo inference。

歷史 Phase 1 release 保留所有已發布 revisions，不做線上 GC；本次 policy-2 最小 retention feature 改為下節的受保護回收。磁碟滿的準備失敗仍不得移除既有 READY。最低限度 registry／immutable publication 是 Phase 1 前置，不延後到 Phase 2。

## Phase 2 — 約 9 個 Repositories 的運行管理

本次最小功能：policy 2／新 namespace；non-current 從被取代起保留至少
30×24 小時，預設 24h fixed-delay idle 檢查，可停用；同一 dispatcher 回收，
跨程序 shared read guard 保護正在讀取的來源，pending intent 驅動安全恢复，
gcRunId 串接操作 log。沒有 quota、額外 worker、管理 API 或九個真實 repo 壓測。
操作與重新準備邊界見 [revision retention](operations/source-mcp.md#revision-retention-and-recovery)。
以下完整多 repo 運行項目仍是規劃：


- 沿用 Phase 1 identity、registry 與 manifest，不改 Source of Truth。
- 加入 retention、受控回收、磁碟 quota、concurrent read 與單 dispatcher fetch 排程。
- Retention 保護 current 與有效讀取窗口；過期 context 明確失敗，不改讀 latest。
- 驗收：9 repos 同時 READY，並行讀取不混 revision；回收不截斷進行中的 response；Git 更新不阻塞既有 source reads。
- 驗收記錄 idle／peak RSS、process count 與 fixture 大小；不得以「9 repos」推論固定記憶體數字。此階段仍是零 JDT LS processes，沒有每 repo 常駐 JVM。
- 回退：停止 GC／回到保留所有 revisions；已刪除 source 只能從相同 Git SHA 重建，不能承諾資料回復。

## Phase 3 — Lightweight Code Index

只有 Phase 1 已驗收、Phase 2 的多 repo 運行邊界穩定後才重新設計。

- 評估 JavaParser 或 Tree-sitter，選一個；SQLite 存 symbol/index state，不存 authoritative source。
- 新增 `search_code`、`get_outline`、`list_entry_points` 與獨立非同步 `prepare_semantic_index`。
- Index identity 至少含 repository、revision、path、symbol、kind、owner、range；失敗／未解析必須如實表示。
- 驗收：索引能引導 `read_source` 回到同一 revision；刪除／損壞 index 時五個 source tools 仍正常。
- 回退：停用新 tools／重建 index，不 rollback source publication。

## Phase 4 — Relations

- 新增 `find_relations`：caller、callee、implementation、reference、HTTP、event、DB evidence。
- 區分 syntax candidate、resolved relation、external／unresolved target；不可把名稱相同當作已確認呼叫。
- 每個可定位的端點都能回到同一 repo/revision/path/range。
- 驗收：已知 fixture 正例與 ambiguity／unresolved 反例；停用 relations 不影響 Phase 1–3。
- 回退：撤除 relation projection/tool，保留 source 與 symbol index。

## Phase 5 — JDT Precision Fallback

- 僅在 overload／generic／inheritance／classpath ambiguity 等需要精確解答時使用。
- on-demand pool，`maxActiveJdtLs` 預設 1、可設 2；memory limit、idle shutdown、LRU、metrics、revision-bound cache。
- 不讓 Query 啟動 JDT；precision worker 使用獨立可丟棄 workspace，不修改 published source。
- 驗收：9 repos 請求也不超 pool 上限；失敗／timeout 保留未解析狀態與 source access。
- 回退：關閉 precision resolver、清理可重建 cache；不影響 source/index 讀取。

## Phase 6 — Cross-repo Intelligence

- 以多個固定 contexts 組成 revision vector，不發明跨 repo 的單一 latest revision。
- HTTP client → endpoint、producer → consumer、shared contract 等關係保留雙側 source provenance。
- 每個 repo 分別授權；另一側不可見時不得洩漏其名稱、path 或 evidence。
- 驗收：跨 repo fixture 有可追溯鏈；不同 revisions／權限遮蔽下不猜測關係。
- 回退：停用 cross-repo projection/tool，不改各 repo source contexts。

## 切換與退路

- 首先以新資料根目錄部署 Source-first 版本，不讀、不轉換舊 Mongo source。
- 切換會移除舊 13-tool catalog 中的 semantic/review/Git history 部分及舊 context union；所有 clients 一起改用新契約，不留 aliases。
- 舊資料可刪，但**刪除後就沒有舊產品的資料 rollback**。需退回舊功能時，重新部署舊 release 並依舊流程 rebuild；不能讓舊 Query 讀新 source volume。
- 發布 B 失敗保留 A；新版本程式回退使用相同已支援的 source manifest format。
- 清除僅限明確屬於本服務的舊 Mongo namespace、managed checkout、JDT workspace 與生成證據；不清除 Git remote、開發者 clone、credentials 或未追蹤的 IDE 檔。

## 文件與規則同步

Phase 1 runtime 已切換為「Indexer 準備並發布 immutable source；Query 只讀已發布 source、禁止 Git mutation 與 JDT」。`AGENTS.md`、`README.md`、operations guides、OpenAPI、MCP schema、Dockerfiles、CI 與 image isolation checks 必須維持同一契約。啟動與 recovery 見 [Source MCP 操作](operations/source-mcp.md)，實際命令、證據與尚未執行的環境驗證見 [測試與驗收](operations/testing.md)；程式或文件更新不等同完整 release 已通過驗收。

舊驗收報告證明舊版，不得被引用成新 Source MCP 的驗收結果。本次重建的詳細現況證據、類別清單、工作包與驗收矩陣保存在本機設計 vault；此 roadmap 本身可獨立供團隊閱讀。
