# Java Code Intelligence — Source-first Repo Intelligence MCP

## 目前狀態

Phase 1–3、Repository business discovery 與 MCP reading／default-branch current milestone 已完成。**Phase 4 Relations 本機 milestone 已完成：實作、最終 ordinary／native 驗證及 whole-branch final review 均已結案；Luna 驗收是 final-review 修正前快照的獨立證據。** Phase 5 未啟動。**Release 尚未驗收**：image／CI／正式部署安全與容量 gates 未執行。最新證據見 [測試與驗收](operations/testing.md#phase-4-local-evidence--2026-10-10)。

**Repository onboarding／依賴準備 skill 已完成小型專案的新 agent 實測**，包含
最新 production executables 上的独立 Query-only Luna 驗收；這是新增的窄範圍證據，
不是重新執行上述 Phase 4 全部 A/B 案例。見
[onboarding 驗收](operations/testing.md#repository-onboarding-local-evidence--2026-10-10)。

**較早的 Module-aware Guide MVP 已執行 Query-only Luna 驗收，但原精確引用關卡未通過。**
OMP-style `read_source` 編號文字已實作並通過一般／native MCP 驗證，原始 JSON 契約
不變。該輪 current 模型引用通過；historical 答案仍留下錯誤縮短路徑，雖然同段
補上正確版本，原判定保持不變。本 MVP 不新增關係 API、索引或差異化授權。

**Agent 預生成導覽與發布交接已完成本機 milestone：實作、三個 scoped 情境與最終 review 結案。**
Author 讀固定 source 生成四份待審閱文件；獨立 Luna 經 Query MCP 查證 current、
historical 與薄導覽漏項情境。歷史版修引用時曾誤讀未指定 SHA，保留 FAIL；
釐清版本邊界後的新情境 exact-context audit 通過。見
[本輪證據與限制](operations/testing.md#pregenerated-navigation-local-evidence--2026-10-11)。
該輪未執行人工內容核准／中央 Git 正式發布及 ES／外部文件 MCP；後續文件 MCP
進度另列下方。Phase 5 自動跨 repo intelligence 暫緩，完整40碼 SHA 與 source API 契約不變。

**前一里程碑：文件搜尋 MCP 已完成本機實作、驗證與 whole-branch final review。**
獨立 catalog、search/read/cursor、stateless transport 的 task review 已結案；
35個 document tests、Source/Relations與Document native journeys 通過。四個選定
Luna 情境經獨立證據 review 為 PASS_WITH_RECOVERY；舊失敗答案保留，範例仍是 TEST_ONLY。
[文件 MCP milestone](#文件-mcp-milestone)。

**目前里程碑：local Git 文件更新與 repository management skill 整理已完成實作、本機驗證及 whole-branch final review。**
文件庫無 remote；local 工具負責具名 Markdown 的 catalog/digests、local commit、
activation/status，Document MCP 保持 immutable read-only。三個入口為
manage-repository／describe-repository／query-business-code；source refresh、
exact historical prepare、文件、optional index 不綁成整套。本輪已完成真實 CLI/MCP、
新 operator（PASS_WITH_RECOVERY）與獨立 Query-only Luna 雙 MCP（PASS）；
不沿用前輪結果冒充本輪驗收。見 [local 操作 milestone](#local-git-文件更新與管理-skill-milestone)。


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
- Source context 固定 repository／40-lowercase SHA；每個 SHA 有獨立 selected canonical UUID index generation。A／B 可同時讀；新建或 rebuild 索引限 exact published current。舊 generation 只在仍保留時可讀，沒有最短 TTL，cursor 不偷換 generation。Phase 3 原始「current B 時 rebuild A」驗證是舊政策歷史證據，不是現行操作方式。
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

**本機實作與驗收完成；whole-branch final review 無未結案 findings。**
caller/callee 不屬於前一個 MCP reading milestone。

- Indexer opt-in JavaParser Symbol Solver 3.28.2；durably frozen roots、approved
  ordered local JARs、Java 21 identity，獨立有界 child JVM 建 profile 3。
- `find_relations`／`POST /api/v1/relations` 使用 exact context＋必填 generation，
  提供 static CALL／CONSTRUCT／METHOD_REFERENCE／EXTENDS／IMPLEMENTS／OVERRIDES／
  REFERENCE 與候選 HTTP／event／DB evidence；不是 runtime call graph 或跨 repo join。
- 保留 syntax-only profile 2 預設與 profile 1/2 歷史讀取；停用 Relations 不影響
  source/raw/fold/syntax 導覽，既有 sealed generation 不重寫。
- 最終 ordinary：317 tests PASS（Model 9／Indexer 172／Query 136）。
  相同最終 production executables 的 source 與 Relations native journeys 各自 PASS，
  含真實 worker/JNI failure、READY preservation、A/B／rebuild pins、raw Git
  bytes、expiry、compact constructor、approved JAR `java21` 與 cold Query。
- 明確 `gpt-6-luna`／Codex 0.162.1：21 次 Query-only MCP calls、A/B 各四頁 CALL
  讀完、六次 raw byte 核對、正確 static/interface/unresolved 限制與 exact 引用。
  先前四輪不完整／錯誤結果仍保留；不把 CLI exit 0 當成驗收。
  此為 final-review 修正前快照的驗收；修正後由 ordinary／native 驗證，未重新跑 Luna。
- 已結案：compact constructor、artifact provenance、parent-death worker temp 與
  interrupted cleanup authority 四項 production findings，及新增 native fixture
  的 incoming-row／Git metadata assertion 修正。Image／CI／正式部署安全與容量未執行；
  Kafka/Rabbit 真實 approved-artifact positive 與獨立 association overflow 未實測，
  不由 JDBC、uncertainty、資料庫容量或本機結果推定。
- 安全回退先停用新 Relations admission，保留 source 與受支援 sealed generations；
  舊程式遇到 profile 3 應明確不相容，不得偽裝 profile 2 或改寫 publication。

## Repository onboarding and dependency preparation — locally verified

- Repository management 入口現為 [manage-repository](../skills/manage-repository/SKILL.md)，
  保留既有 registration／allowlist、exact-current source、核准 roots/JAR、syntax
  或 opt-in Relations、Query 驗收與 DRAFT 描述交接；下列仍是當時窄範圍證據，
  不代表新 local 文件操作或改名後情境已通過。
- 新 operator agent 實際 onboard 一次性小型 Maven-descriptor repo，依 exact POM
  選定 Commons Lang 3.20.0，未使用另一個 3.19.0 cache；source／profile-3 index
  均 COMPLETE，external JAR target 與 digest 經 raw source 核對。
- 停止 Indexer 後，獨立 `gpt-6-luna` 完成 10 次 Query-only calls、兩頁 CALL、
  兩個 raw Git-byte 核對及四個 exact repo/SHA/path/line 引用。
- 前兩輪模型回答因擴大外部 API 行為主張、產生虛構 client-local 引用而未通過；
  已補一般 grounding／citation 規則，保留失敗證據並以新的第三輪驗收。
- 不新增 Indexer 自動下載或建置功能。未驗證 Maven／Gradle effective-model
  執行、私有套件下載、多模組複雜依賴、正式部署或 image／CI；不宣稱所有 repo
  均可自動成功。Phase 5 未啟動。

## Module-aware Guide MVP — pending runtime acceptance

- 沿用 `PROJECT_GUIDE.md`／`project-guide-path`，以固定 Markdown 欄位記錄
  application、library、aggregator 與 unknown modules，附路徑、入口、用途、
  exact-source 證據、人工說明及未涵蓋範圍。
- BUILD_DEPENDENCY 與 HTTP_CALL／EVENT_PUBLISH／EVENT_CONSUME／SHARED_CONTRACT
  分開描述；文件 labels 不是新的 MCP enums，也不會建立已驗證 graph edges。
- 同 repo modules 共用固定 SHA，跨 repo 各自固定 context；先依 guide 導覽，
  再讀雙側 source／必要設定。Guide 分析 SHA 不同不自動判定過時，實際衝突以
  指定版本的原始碼為準；可執行入口不等於已部署服務。
- 本 MVP 假設所有內部讀者可查全部納入的 repo／module，不新增 ACL 或關係
  遮蔽；既有 Query token、allowlist、Indexer 管理隔離與 secret hygiene 不變。
- 驗收定義見 [module-aware guide acceptance](operations/mcp-agent-acceptance.md#module-aware-guide-acceptance)：
  兩個 repo，其中一個含兩個 application entries 與共用 library；核對 build
  宣告、HTTP 候選、過時 guide、不同版本及缺少部署設定。必須由明確指定
  `gpt-6-luna` 的 Query-only client 實際讀取，不能以文件或 fixture 建立代替。
- 範本與 skills、兩個合成 Git repos、current／historical 情境已完成並通過
  靜態 review；文件連結、fixture Git/blob/byte/XML 及引用範圍檢查已通過。
- Host 容量恢復後，已用 bounded JVMs 與明確指定 `gpt-6-luna` 的獨立 Query-only
  clients 執行 current／historical 情境。自建程序 RSS 合計取樣峰值約 438 MiB。
- 已驗證 source-only 讀取、獨立 SHA、module／build 宣告及 endpoint 差異；
  模型曾送出錯誤 cursor／depth，server 明確拒絕，後續查詢完成且未改換版本。
- 已更新 query skill：結論綁定原文、LF 行號、guide/source 雙側引用、交付前
  raw ranges 核對及逐字沿用識別。歷史情境重跑的 R3 引用涵蓋真正的 L52。
- 先前純 skill 複測未通過：current 回答把 `apps/member-api/...` 改寫成不存在的
  `apps/member/api/...`；補上識別複製規則後仍再次發生。不能只靠更多提示或重跑
  宣稱已解決。該輪失敗答案、MCP traces 與範圍／識別審核均保留；見
  [實際驗收紀錄](operations/testing.md#module-aware-guide-verification-status--2026-10-10)。
  此 milestone 尚未驗收完成；正式 Phase 5 跨 repo projection/index 仍未啟動。
- OMP-style 讀檔呈現已完成：成功的 MCP `read_source` 文字提供精確 locator、
  原始 LF 行號、半行／省略標記與完整頁面 metadata；HTTP 與 structuredContent
  保持原始 SourceResult。Wire budget 計入新呈現，相關 native／image callers 已遷移。
- 320 項一般測試、2 項實際 native MCP journeys 通過；新執行檔上的 Luna current
  回答 22 筆引用通過。Historical 有 16 筆有效引用，但還留下一筆 `MemberApi.java`
  縮短路徑；自行在同段補正不等於刪除錯誤引用。因此編號功能完成，模型引用關卡
  仍未通過。此輪 model-smoke 程序 RSS 合計取樣峰值約 434 MiB，不含 Maven/native lane。

## Phase 5 — Cross-repo Intelligence

- 以多個固定 contexts 組成 revision vector，不發明跨 repo 的單一 latest revision。
- HTTP client → endpoint、producer → consumer、shared contract 等關係保留雙側 source provenance。
- 每個 repo 分別授權；另一側不可見時不得洩漏其名稱、path 或 evidence。
- 驗收：跨 repo fixture 有可追溯鏈；不同 revisions／權限遮蔽下不猜測關係。
- 回退：停用 cross-repo projection/tool，不改各 repo source contexts。

## Milestone — Agent 預生成導覽與發布交接（實作與本機驗收）

**已確認架構：** author agent 直接讀核准的固定 commit → 集中式 Git 文件庫 →
人工核准版本 → 未來 ES 搜尋索引／外部文件 MCP → query agent → 既有 codebase MCP。
中央 Git 保存正式文件與歷史；ES 是可重建的搜尋索引，不是獨立維護的第二份原稿。
文件 MCP 與 codebase MCP 各自獨立，由 agent 串接；本服務不直接依賴 ES。

### 本輪交付與邊界

1. **預生成流程：** author agent 可直接探索核准專案的 tracked source，但固定完整
   SHA，不混入未提交內容、忽略檔、credentials 或本機建置產物，不執行目標專案。
   可讀整體專案不等於逐檔讀完；明列已讀／未讀範圍。
2. **既有模板調整：** 沿用短版描述與 module-aware guide，提供 repo 總覽、
   module 章節、業務詞／識別字／程式入口，以及有雙側證據或未知標記的跨 repo
   關聯。目的在「去哪裡查、為何先查」，不是預先複製所有公式與業務答案。
3. **集中式發布交接：** `.local/repository-descriptions/` 僅作草稿暫存；
   每次更新都須人工核准確切內容與分析版本，另經授權交接到獨立中央 Git 文件庫。
   不要求提交至各業務 repo，不自動改服務設定、prepare 或發布。
   本輪不建立實際遠端文件庫，不把設計核准冒充產出內容核准。
4. **外部搜尋輸入契約：** 定義文件 repo／文件 revision／path／章節定位、
   source repositoryId／analyzedRevision、審閱與 trust metadata；搜尋片段需能
   取得相同文件版本的完整章節。文件 commit 與業務 source SHA 必須分開，
   文件版本由發布層附加，不要求文件嵌入自身 commit。
   僅定義核准版本輸入與舊章節替換規則，不實作 ES ingestion 或文件 MCP。

### 文件生命週期與查詢規則

- 每個 repo 維護最新核准導覽並保留 Git 歷史，不為每個 source commit 生成文件。
  明示按 repo 更新；生成失敗或等待審閱時，原核准版保持不變，不新增自動排程。
- 最新導覽查歷史 SHA 時只是導航線索；路徑不存在即回該版本 list/search，
  不切换 current。分析 SHA 不同不自動判定內容過時，缺少或過期證據明示不可查。
- 文件只決定優先順序；完整不帶 filter 列舉 repo，再深入文件命中的候選。
  無命中、證據不足或跨 repo 線索時擴大查詢，不把未查範圍說成不存在。
- 文件與 source 衝突時，以指定版本 source 查證；跨 repo 各自固定 context，
  method／path 或名稱相同不證明部署與 runtime wiring。文件始終是 NOT_VERIFIED
  導覽，人工核准不是 server verification。
- 既有 `navigation-hint`／`project-guide-path` 仍是受支援的 server 功能；
  新的中央文件流程不依賴它們發布，不為此次切換移除其 API 或重寫 source。
- 保留完整 40 碼 SHA、完整 repo-relative path、原始行號；不新增短引用 ID、
  短 SHA、引用解析服務或 codebase MCP 欄位。

### 驗收與執行順序

- 先定 canonical 模板及文件交接契約，再分開調整 author/onboarding 與 query/
  acceptance skills，整合 README／操作指南；共享文件由單一整合者負責。
- 用既有多 module／多 repo fixtures，由 author agent 真正讀固定 source 生成
  新導覽，不把人工預寫 guide 或舊答案當作生成結果。
- 獨立且明確指定 `gpt-6-luna` 的 query agent 只取得文件測試輸入與 Query MCP，
  不取得本機業務 source、作者推理、oracle 或 Indexer 權限。核對業務查找、
  歷史版本、文件漏項／衝突及關聯另一側未知，保留完整 trace 與原始答案。
- 分開記錄引用識別／範圍有效、原文支持結論、以及模型是否明確自行更正。
  後續人讀答案驗收允許「明確指出錯誤引用並為同一主張提供已讀、有效且支持
  結論的更正」通過並保留恢復紀錄；僅追加無關引用、未更正或無證據仍不通過。
  不手動修模型答案，也不宣稱可安全擷取所有出現過的 locator。
- 舊 Module-aware Guide／numbered presentation 驗收保留原判定，不追溯改成通過。
  本輪文件由驗收流程明示交付，不能宣稱已測外部文件搜尋；草稿測試也不證明
  已完成內容審閱或正式發布。分開列生成、交接、code 查證、發布及外部整合狀態。
- 依 host headroom 與既有安全規則使用單一有界驗證 lane、一次性 roots；文件修改
  不要求 Maven，實際 agent／MCP 情境另行執行。保存工具／讀取量與耗時，
  沒有可比基準不宣稱效能改善。完成後凍結 writers 並 review。

**不包含：** ES／外部文件 MCP 建置、向量搜尋、自動更新排程、新跨 repo 索引／
匹配 API、runtime graph、新 ACL、JDT、強制 JavaParser indexing、依賴下載、
既有服務／資料變更、Docker／image／CI 或 project commit／merge／push。
本輪已更新 canonical 契約、author／query／onboarding skills 與入口文件，並完成
真實模型生成及隔離 Query MCP 情境。正式發布仍需人工內容核准；範例測試不需等待。
尚未建立／發布中央文件庫或執行外部搜尋整合。既有 token／allowlist／Indexer 隔離與 release gates
不變，Phase 5 自動 projection 仍未啟動。失敗、修正與各 gate 狀態見上方驗收證據。


## 文件 MCP milestone

**目標：** 不再把四份導覽全文預先塞入 query prompt。Agent 先從獨立文件 MCP
搜尋與讀取文件，找到 repo／module／source 起點，再用既有 codebase MCP 查證。
採集中式 Git 文件快照及有界 literal 搜尋；Elasticsearch 留待後續規模需求。
新增獨立 `document-query`，不在 Source Query 引入 Git、ES 或模型依賴。
本輪實作與隔離測試已核准；正式發布及部署仍需另外授權。

### 實作順序

1. **測試文件庫與版本交接。** 沿用目前兩個範例、四份已稽核生成文件，建立
   隔離的中央 Git 測試文件庫。由交付層附加實際文件 commit、path、章節定位、
   source contexts 與 test-only provenance；不嵌自身 hash，不更動業務 source。
   測試資料與正式可發布資料分開，範例不用逐份人工審閱，但仍核對引用與支持度。
2. **獨立文件 MCP。** `search_documents` 有界分頁，命中附 exact 文件身分與
   section locator／snippet；`read_document` 固定該文件 commit，回傳有上限的
   單一完整文件及所選章節，含 header、contexts、evidence 與限制，不偷偷改版。
   沿用中央交接契約，不增加 codebase MCP 的資料庫、ES 依賴或請求欄位。
3. **Agent 串接。** 更新 query skill，先文件搜尋、再讀所需章節、最後查 source。
   無命中或文件不足時回 codebase 的完整 repo discovery 與 source navigation；
   文件不限制可查 repo，也不能用分析 SHA 覆蓋使用者指定 SHA。
4. **隔離端到端驗收與 review。** 明確指定 `gpt-6-luna`，查詢端只接文件 MCP
   與 Query MCP，不給本機 source、預載導覽全文、author trace 或 Indexer。
   保留兩個 MCP 的完整 trace、版本身分、答案與失敗紀錄，再作獨立 review。

### 完成條件

- 業務問題能經「文件命中 → 完整章節 → exact source」取得支持結論的證據。
- 最新文件搭配指定歷史 source 時維持各自版本，不擅自擴大 source revisions。
- 文件漏項／無命中能擴大查找；相同 method/path 仍只證明候選，不聲稱部署關係。
- 文件更新後搜尋與讀取不混版；更新失敗不破壞原可讀版，舊命中不偷偷改指新版本。
- 範例可直接驗收，但始終標示 test-only／NOT_VERIFIED；正式发布規則不被繞過。
- 新服務啟動前檢查 Linux／host 容量，單一有界驗證 lane、一次性 roots，完成後
  清理；沒有實際 headroom 證據不宣稱資源可承受。

詳細設計／計畫保留於本機 `docs/superpowers/`；四個 task review 與修正已完成。
新搜尋只讀 active 文件版本；cursor／明確讀取可繼續使用 retained 舊文件版本。
文件與 source SHA 獨立，不為每個 source commit 重產文件。實際 ordinary/native、
雙 MCP Luna 的結果與重試／拒絕紀錄見
[文件 MCP 驗收](operations/testing.md#document-mcp-local-evidence--2026-10-11)。
Whole-branch final review 已結案、無未處理發現；本機成功不等於 release/image/CI 已通過。
不包含 ES 部署、向量搜尋、自動更新排程、runtime graph、新跨 repo projection、
真實業務文件發布或主專案 commit／merge／push。已完成 CI routing 與既有
Dockerfile reactor-POM 相容修改；未執行 Docker／image／CI，也未新增文件服務 image。

## Local Git 文件更新與管理 skill milestone

**實作、本機驗證及 whole-branch final review 已完成，無未處理發現；release 未驗收。** 核准範圍為獨立無 remote 的 local 文件庫，不需
URL、credentials、fetch/pull/push。Update 只提交具名 Markdown 與 generated
catalog；activate/status 與 source/index 各自獨立，作者不手填 JSON/self-hash。
保留使用者 dirty bytes、舊 retained identity、明示撤回及失敗時原 active；
local commit/selection 不假稱 running MCP 已採用。

- 管理入口 clean rename 為 manage-repository，無 alias；重型 Java/Relations
  detail 保留單一 reference。describe 負責作者／審閱交接，query 保持唯讀查證。
- 正式 APPROVED 仍須人工核准確切內容／版本及獨立啟用授權；已授權範例
  維持 TEST_ONLY/DRAFT/NOT_VERIFIED，不要求逐份人工審閱或冒稱核准。
- 本輪已完成三個 task review、實際 publisher 缺陷修正、54 document ordinary
  tests（53 pass／1 unsupported-ACL skip）、3 document native 與2 source/Relations
  native 情境。修正前完整 reactor365 tests 通過；不把歷史 snapshot 重新計為本輪測試。
- 六個 guidance baseline/green 情境是文字路由證據；新 operator 實際使用 local CLI，
  獨立明選 gpt-6-luna 以9次雙 MCP 呼叫完成 exact source 查證。保留 operator
  的兩個 setup recovery，不將機械 checks 或文字路由冒充端到端證據。詳見
  [本輪證據](operations/testing.md#local-repository-operations-evidence--2026-10-11)。
- 不授權既有服務變更、網路／下載、Docker/image/CI、正式資料發布或主專案
  commit/merge/push；此前歷史失敗與 release limits 保留。

## 發布與回退

- 發布失敗保留既有 READY；相容程式回退必須使用受支援的 source manifest 與 index profile。
- Index rebuild 不改寫 source publications；不相容的 schema/profile 使用新 namespace 與 matching writer/reader。
- 回收必須遵守讀取保護與 durable intents。已刪除 source 只能在核准 remote 仍提供相同 SHA 時重新準備。
- 部署、rebuild 與 rollback 的完整順序見 [Release and recovery](operations/source-mcp.md#release-and-recovery)；不授權刪除其他服務、開發者 clone、credentials 或 IDE 資料。

## 文件與規則同步

`AGENTS.md`、`README.md`、operations guides、OpenAPI、MCP schema、Dockerfiles、CI 與 image isolation checks 必須維持相同的 source／index 契約。操作細節見 [Source MCP 操作](operations/source-mcp.md)，驗證命令與結果集中於 [測試與驗收](operations/testing.md)；程式或文件更新不等同完整 release 已通過驗收。
