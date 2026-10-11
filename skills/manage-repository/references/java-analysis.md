# Java analysis inputs and acceptance

Use only for explicitly requested syntax/fold or static Relations. JavaParser is bundled with Indexer; do not install a parser/JDT per repository. Java 21 applies to Java analysis, not document drafting. Report incompatible syntax/platform requirements, missing private artifacts/authorization or unknown capacity; do not switch versions or guess dependencies. Syntax/fold keeps Relations disabled, needs no external JAR or target compilation, and prepares only exact published current. Generated code is not invented.

## Relations inputs

### Source roots 與依賴清單

讀 exact SHA 的 Maven／Gradle descriptors、parent/BOM、dependency management、properties、lockfiles、profiles、source sets 與 module 列表。只讀檔不足以確定有效配置時，列出歧義，不憑 artifact 名稱挑最新版本。

- 列出實際存在且不重疊的 repository-relative source roots；多模組各自納入。選定 production/test 範圍，不無條件加入所有 tests 或 generated directories。
- 同 repo 依賴模組優先以已納入的原始碼分析；另一 repo 不會自動成為 source root，也不代表支援跨 repo join。
- 整理需要的直接及間接 compile/provided dependencies；test dependencies 僅在核准分析 test roots 時加入。記錄 exact 版本、type/classifier、來源、順序與選擇理由；不將 plugin、processor 或所有 runtime artifacts 盲目加入。
- 重複 class/provider 需明示並維持核准順序；不任意排序、猜優先權。JDK 由服務提供，不另放另一版本的 JDK classes。

### 取得與核准

優先使用操作者核准的現有 artifact／唯讀 cache。把選定普通 JAR 複製到服務可讀、受控的私人輸入位置，計算實際 bytes 的 SHA-256；記錄座標、來源、大小、digest、source roots 與 analyzed SHA。Hash 固定內容，**不證明來源可信**；仍需來源核准。依賴準備不是 Indexer 的下載功能。

沒有可用 JAR 時，先取得明確的 artifact registry/network/credential 與命令授權。`mvn`／`gradle` 即使只解析依賴也可能載入 extensions/plugins 或執行 build scripts；不可逕自在目標 repo 跑 wrapper/build。需要時在另行核准的隔離環境執行受審閱的解析流程，使用受控倉庫及一次性 cache，不改 global settings 或共享 cache。無法確定 effective dependencies 就停止或經使用者明確同意採 partial 模式，不靜默縮減。

檢查當前 [Relations 輸入契約](../../../docs/operations/source-mcp.md#static-relations)：普通／受支援 multi-release JAR、無 symlink、無 nested/fat-JAR layout，遵守個別與總容量／class 數限制。不要直接把 Spring Boot executable JAR 當依賴集合；取得各個普通 artifact，不能未經授權解包或建置目標程式來補齊。Annotation processing、Lombok 生成 members、反射及 runtime wiring 不會因此變成可靠事實。

## 啟用與建立索引

1. 在私人 Indexer 外部設定填入 `semantic.repositories.<id>.relations.enabled: true`、`source-roots` 及 ordered `dependencies`。每個 dependency 使用 `id`、Indexer 可見的絕對 `path`、實際 lowercase `sha256`。路徑必須在 runtime 可見，不只是 agent host 看得到。
2. 經授權讓 Indexer 載入設定；不得重啟未獲核准的既有服務。Query 不需要 dependency mounts，也不執行 parser。
3. 確認先前 exact SHA **仍是 current**；變更了就停止並重新確認新來源／依賴，不自動換 SHA 或拿舊依賴分析新 commit。
4. 持久記錄另一個新 UUID，對該 exact SHA 呼叫 `prepare_semantic_index`，等待 `get_job.phase` 為 `COMPLETE`。若已有舊 syntax generation，仍以新 requestId 明確準備新的 Relations generation；沒有額外的 `rebuild` request 欄位。不得以舊 generation 當新能力成功。保存 generation、profile、job/request 與 public provenance。
5. Indexer 會凍結 JAR／analysis inputs；已接受工作不受之後設定改動影響。失敗要記錄 domain error 並保留既有 READY，不刪資料「重試」、不改寫 sealed generation、不自動提交新 request。

## 驗收

依 [agent 驗收規則](../../../docs/operations/mcp-agent-acceptance.md) 與 [測試安全範圍](../../../docs/operations/testing.md#agent-model-for-mcp-query-acceptance) 驗證：

- 先確認 Query 可見 repo、exact context 與 source READY，並讀取代表性 raw source。只有要求 syntax/fold 或 Relations 時，才驗證 index capability 與 outline；source-only 的 index／outline 驗收不適用，不為了驗收擅自建立 index。
- 要求外部依賴解析時，至少選一個實際外部依賴呼叫，檢查 exact generation 的 `find_relations`、target 簽章、artifact digest 與 raw source citation；不能用 JDK 或本 repo 的呼叫冒充外部 JAR 成功。缺少代表性呼叫或 resolved fact 就明示未驗證及 coverage/reason，不偽造 target。
- 只有明確核准「不需要外部依賴」的 Relations 範圍，才用代表性 source／JDK 關係及 raw citations 驗收，外部 artifact 解析標為不適用；不能隱藏缺少的必要依賴。含外部依賴的小專案仍須通過 external-artifact gate。依賴可為空，但只承諾可見來源與 Java 21 平台能解析的部分；缺少外部型別保留 unresolved／partial。
- 外部 target 的簽章／digest 不證明其方法本文；未讀到相符版本的 implementation／API 契約時，只將 caller 呼叫與分支當作 source 證據，不把名稱推測出的 null／例外等行為當成已查證事實。
- 完整列舉走完 `hasMore=false`，保留 exact context/generation/anchor/filters；generation-wide coverage 不是 filtered row count。
- 另開 **僅 Query 權限**的 `gpt-6-luna`，顯式指定 model，禁用本機 source/oracle 與管理入口；保存 client/version、invocation、完整 MCP trace、回答、exact repo/SHA/path/lines。模型不可用就回報 blocker，不能默換。不承諾 100% 解析或 production release。
