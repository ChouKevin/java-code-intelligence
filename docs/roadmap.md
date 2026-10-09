# Java Code Intelligence — Source-first Repo Intelligence MCP

## 目前狀態

Phase 1 source navigation、Phase 2 retention／容量門檻、Phase 3 lightweight Java index 與 Repository business discovery 已實作。本次 **MCP reading／default-branch current milestone 已完成實作、獨立 final review 與本機驗收**：248 ordinary tests、packaged native journey、真實 Codex／MCP 讀檔與引用流程均通過。**Release 尚未驗收**：image／CI／外部 syntax-index tools 模型操作／部署安全／容量 gates 仍未完成。最新證據見 [測試與驗收](operations/testing.md#mcp-reading-and-current-local-evidence--2026-10-09)。Phase 4–5 仍是規劃。

## 不變原則

1. 證據優先序：指定 Git commit 的 code > semantic/symbol index > guide/README/docs。
2. Source tools 必須接受並回傳同一 `repositoryId + revision`；branch 更新不替換已取得的 context。
3. Source readiness 與 semantic readiness 分離；semantic 失敗不能使 source 失效。
4. Query 只讀取已發布 source／index，不 fetch、checkout、啟動 Indexer、parse Java 或執行 repository code。
5. Indexer 持有 Git credentials、準備與發布 source；維持單一程序、單一 dispatcher。
6. HTTP／MCP 共用 facade、輸入驗證、結果與錯誤契約；讀取 token 與準備 token 分離。
7. `semantic-model` 只依賴 JDK；不增加 Spring、Mongo、JGit、parser 或 MCP 依賴。
8. Source response 有 path、line、bytes、筆數與時間上限；所有路徑均受 containment 與 repository authorization 約束。
9. Guide 是 `NOT_VERIFIED` navigation hint，不是 semantic fact，也不是工具執行指令。

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
          +-- sealed SQLite index generations ---+
          (JNI temp stays private to each process, not on published mounts)

                    semantic-model
              repository / revision / source contracts
```

- 保留三個 Maven module 名稱，不同時做 rename。
- Query 使用唯讀 filesystem／ripgrep source adapter；不引入 JGit。
- Indexer 使用既有 JGit 技術與安全驗證，從 exact commit 匯出 immutable revision directory。它不是會被 `reset --hard` 重用的 mutable checkout。
- 私有 Git object database、jobs、staging 與公開 source volume 分開；Query 不掛載 credentials、`.git` 或 staging。
- Source registry／revision manifest／current pointer 使用小型檔案 metadata，不使用 Mongo。
- Phase 3 以 SQLite 保存可重建 syntax index；source 仍是 authoritative evidence，不把 native/JDBC failure 變成 source startup failure。

## Phase 1 — Source-first Repo MCP

### 交付

已完成五個 source tools：

| Tool | 責任 |
| --- | --- |
| `list_repositories` | 顯示已註冊且授權的 repo；READY item 帶目前 revision，未準備者不捏造 SHA |
| `get_context` | 讀取已發布 current 或指定已準備 revision，不觸發 fetch／prepare |
| `list_files` | 固定 revision 的 direct-child navigation，穩定分頁，不回傳整棵大型 tree |
| `search_text` | 固定 revision 的 literal ripgrep search，含 bounded results、timeout、truncation disclosure |
| `read_source` | 固定 revision 的檔案／行範圍讀取，最多 500 行、預設最多 64 KiB content，支援無缺漏續讀 |

Private Indexer 的 `prepare_source`、`get_job` 提供 durable preparation 與工作查詢。Source 使用 exact revision、immutable publication、獨立 Query 及一致的 HTTP／MCP 契約；不依賴外部資料庫。現行驗收規則見 [Source journey](operations/mcp-agent-acceptance.md#source-journey-驗收)。


## Phase 2 — 約 9 個 Repositories 的運行管理

已實作 policy 2：non-current 最少保留 30×24 小時、預設 24h fixed-delay idle
檢查、同一 dispatcher 回收、跨程序 shared read guard、pending intent 恢復、
gcRunId 操作 log 與應用層容量門檻；不增加 worker 或管理 API。
操作邊界見 [revision retention](operations/source-mcp.md#revision-retention-and-recovery)
與 [capacity admission](operations/source-mcp.md#application-capacity-admission)。

- 沿用 Phase 1 identity、registry 與 manifest，不改 Source of Truth。
- 現行容量門檻統計 private／source／index 的常規檔案 logical bytes，另可設定最低可用空間；兩項預設停用。
- 超過門檻或無法安全量測時，準備 job 明確失敗，保留既有 READY；不提前 GC、不自動重試。
- 此階段選擇應用層檢查，不提供檔案系統硬 quota；fetch／export 執行中與其他寫入仍可能超出門檻。
- Retention 保護 current 與有效讀取窗口；過期 context 明確失敗，不改讀 latest。
- 驗收：9 repos 同時 READY，並行讀取不混 revision；回收不截斷進行中的 response；Git 更新不阻塞既有 source reads。
- 驗收記錄 idle／sampled peak RSS、process count、取樣間隔與資料量；短時取樣不代表絕對峰值，小型 repo 也不代表正式工作負載容量。沒有每 repo 常駐 JVM。
- 回退：兩個容量門檻設為 `0` 可停用檢查；停止 GC 可回到保留所有 revisions。已刪除 source 只能從相同 Git SHA 重建，不能承諾資料回復。

## Phase 3 — Lightweight Code Index

**實作、本機 native 驗證與獨立 final review 完成；release 未驗收。** 完整證據與未執行 gates 見 [testing](operations/testing.md#phase-3-local-evidence--2026-10-09)。

- 選定 JavaParser core `3.28.2`／`JAVA_21`、一次 JLS 3.3 Unicode translation／raw-boundary mapping／tab size 1 與 SQLite JDBC `3.53.4.0`。Indexer syntax-only，不 build repo、resolve classpath 或生成 methods；parser stack exhaustion 只令該檔 PARSE_FAILED，無 partial facts。
- Query 增加 `search_code`、`get_outline`、`list_entry_points`（總共八 tools）；Private Indexer 增加 `prepare_semantic_index`（總共三 tools）。先明確 prepare exact source，再以新的 canonical UUID prepare index；原始 request recovery 與跨兩種 jobs 的單一 repo admission 保留。
- Source context 固定 repository／40-lowercase SHA；每個 SHA 有獨立 selected canonical UUID index generation。A／B 可同時讀；新建或 rebuild 索引限 exact published current，舊 generation 在合法 expiry 前保留，cursor 不偷換 generation。Phase 3 原始「current B 時 rebuild A」驗證是舊政策歷史證據，不是現行操作方式。
- Sealed SQLite 與 read-only Query index mount 獨立於 source；JNI 使用 UID-owned 私有可寫／可載入 temp，不能使用 published mounts。容量門檻計入 private／source／index（包含 retained generations），不 force GC。
- Results 明確帶 profile、Java coverage、syntax analysis level、raw UTF-8 byte ranges、blob/content provenance、annotation ambiguity／unresolved values；parse failed／too large 如實表示，無 generated facts。
- 本機 journey 已跑通真實 Git A／B、SQLite build/read、三 index tools 的 HTTP／MCP parity、job receipts、generation-bound continuation／expiry、partial coverage、byte-limited pages，以及 oversized parent／可讀 child。
- 本機測試與 probes 已覆蓋 index-local native failure、source tools 獨立性、durable companion intents／GC handoffs、same-SHA fences，以及真正執行中 SQL 的 deadline／取消／descriptor cleanup。這些不是 image mount／UID 證據。
- 更新部署腳本不代表 image/CI green；被選定的 image／release gates 仍需另行授權與執行。回退／rebuild 順序見 [operations](operations/source-mcp.md#release-and-recovery)，不 rewrite source publications。

## Repository business discovery — locally verified

Implemented optional human-reviewed repository navigation hints and a
source-first query workflow for business rules, calculations, eligibility,
features and service flows. Local verification completed on 2026-10-09:
210 ordinary tests, the clean native journey and six actual Codex/MCP scenarios.
This is not release acceptance and does not implement relations or cross-repository intelligence.

A first-round omission of checked non-answer repo/SHA identities was corrected
in the general skill. A fresh six-case client round delivered all nine exact
contexts in every final answer; the original deviation remains recorded.

- `describe-repository` creates a local DRAFT. A human must approve the exact
  summary, keywords and analyzed revision; an independently authorized operator
  then updates external Indexer configuration and explicitly starts/restarts
  Indexer. No Markdown import, automatic registration, prepare/fetch, or publish
  occurs. Repo-internal `project-guide-path` remains a separate workflow.
- Query discovers the complete unfiltered allowlisted list across pages,
  prioritizes without excluding repos, and verifies findings at each repository's
  independent exact source context. Hints are `NOT_VERIFIED` navigation data,
  never facts or instructions; missing, incomplete, stale, or hostile content
  cannot replace source evidence or authorize access.
- Hints do not make an unprepared source readable. A valid update/removal changes
  the mutable repository catalog and invalidates its old list cursors; source
  formats/policy, private source jobs, index formats and immutable artifacts stay
  unchanged. Application/transport response limits and oversized-item errors
  remain explicit.
- Actual source-only client scenarios covered missing, misleading, hostile and
  stale hints plus independent exact-A pins while current remained B, with
  source-grounded answers and explicit runtime unknowns. See
  [business-discovery acceptance](operations/mcp-agent-acceptance.md#repository-business-discovery-acceptance)
  for synthetic repository/SHA/path/line evidence and unexecuted release gates.

## MCP reading and default-branch current — locally verified

**實作、獨立 final review 與本機驗收完成；release 未驗收。**
保留 Indexer 的 JavaParser core，不進行 parser migration。

- `list_files` 增加有界兩層目錄預覽；`read_source` 增加多段行範圍、
  檔尾、精確原文及 Java 方法／建構子本文摘要，明示省略與續讀。
- current 只由未指定 revision 的明示 default-branch fetch/pin/publication 更新，
  不是即時追蹤遠端 HEAD；指定歷史 SHA 只增加可讀 membership，不改 current。
- 保留兩步管理流程：source COMPLETE 後，以另一個原始 request UUID
  為該 exact current SHA 明示準備索引；查詢不觸發 fetch 或 preparation。
- 只限制新索引建立為 current；已發布的歷史 source/index 仍按 exact identity
  與合法 retention 讀取。歷史發布收據、重用、GC 及重啟恢復一起調整。
- Query 不加入 parser；無索引仍能讀原文。維持 Unicode/CRLF、原始 byte/line
  證據、revision/generation-bound continuation、授權與實際回應大小限制。
- 已驗收 A/B 獨立 session 同時閱讀、current 更新／重建期間的固定版本續讀、
  無索引歷史 H、真實 Codex 的預覽→摘要→原文補讀→來源引用，以及
  multi-range／tail。完整證據與未執行 gates 見
  [testing](operations/testing.md#mcp-reading-and-current-local-evidence--2026-10-09)
  與 [agent acceptance](operations/mcp-agent-acceptance.md#mcp-reading-and-current-acceptance--2026-10-09)。

## Phase 4 — Relations

下一個功能階段；尚未啟動。caller/callee 不納入已完成的 MCP reading milestone。

- 新增 `find_relations`：caller、callee、implementation、reference、HTTP、event、DB evidence。
- 區分 syntax candidate、resolved relation、external／unresolved target；不可把名稱相同當作已確認呼叫。
- 每個可定位的端點都能回到同一 repo/revision/path/range。
- 驗收：已知 fixture 正例與 ambiguity／unresolved 反例；停用 relations 不影響 Phase 1–3。
- 回退：撤除 relation projection/tool，保留 source 與 symbol index。

## Phase 5 — Cross-repo Intelligence

- 以多個固定 contexts 組成 revision vector，不發明跨 repo 的單一 latest revision。
- HTTP client → endpoint、producer → consumer、shared contract 等關係保留雙側 source provenance。
- 每個 repo 分別授權；另一側不可見時不得洩漏其名稱、path 或 evidence。
- 驗收：跨 repo fixture 有可追溯鏈；不同 revisions／權限遮蔽下不猜測關係。
- 回退：停用 cross-repo projection/tool，不改各 repo source contexts。

## 發布與回退

- 發布失敗保留既有 READY；相容程式回退必須使用受支援的 source manifest 與 index profile。
- Index rebuild 不改寫 source publications；不相容的 schema/profile 使用新 namespace 與 matching writer/reader。
- 回收必須遵守讀取保護與 durable intents。已刪除 source 只能在核准 remote 仍提供相同 SHA 時重新準備。
- 部署、rebuild 與 rollback 的完整順序見 [Release and recovery](operations/source-mcp.md#release-and-recovery)；不授權刪除其他服務、開發者 clone、credentials 或 IDE 資料。

## 文件與規則同步

`AGENTS.md`、`README.md`、operations guides、OpenAPI、MCP schema、Dockerfiles、CI 與 image isolation checks 必須維持相同的 source／index 契約。操作細節見 [Source MCP 操作](operations/source-mcp.md)，驗證命令與結果集中於 [測試與驗收](operations/testing.md)；程式或文件更新不等同完整 release 已通過驗收。
