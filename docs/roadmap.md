# Java Code Intelligence — Roadmap

## 目前狀態

本輪 Source／Document discovery 已完成實作與 ordinary／packaged native 驗證：新增檔名 glob 探索、無關鍵字文件瀏覽，以及既有 query skill 的有界專案概覽。獨立 Luna Query 的 current、retained SHA／最新文件分離、空文件 fallback 與 directory scope 已實跑，需修正的案例明列 PASS_WITH_RECOVERY；whole-branch review 與修正複核已完成。這不代表 Docker／CI／正式部署已驗收，也不自動提交或整合工作分支。Source 歷史讀取、current 更新、index admission、retention 與 sealed generations 未改動；詳見 [testing](operations/testing.md#current-verification-status)。

本 roadmap 保留已交付範圍、待驗收事項及未啟動方向。操作步驟以 [Source operations](operations/source-mcp.md) 與 [Document operations](operations/document-mcp.md) 為準；證據狀態集中於 [testing](operations/testing.md#current-verification-status)。

## 本輪 milestone：Source／Document discovery

已實作 `find_files`、與 `search_text.filePattern` 共用的 glob 規則、
latest-per-owner `list_documents`，及現有 query skill 的有界陌生專案探索。
Source 預設先取得 current 再固定完整 SHA；明示已發布 retained SHA 仍可讀。
文件瀏覽不增加文件管理層、不提供任意歷史版本，也不引入新 parser／服務。

本輪實作、ordinary/native、獨立 Luna 與 review 的實際結果另記於
[testing](operations/testing.md#current-verification-status)；舊 milestone 的通過
不構成本輪新工具的驗收。Commit／merge／push 和 image／CI／部署仍需另行授權。

## 已交付能力

| 範圍 | 現有能力 | 主要邊界 |
| --- | --- | --- |
| Source-first MCP | 註冊、durable preparation、exact Git blob publication、list/search/read | Query 不觸發 preparation；註冊不等於 READY |
| 多版本與運行管理 | current／歷史 SHA 同時讀取、retention、read guards、容量 admission、恢復 | current 只由明示 default-branch refresh 更新；容量檢查不是硬 quota |
| Java syntax／folds | JavaParser 3.28.2、Java 21、sealed SQLite generations、syntax search／outline／entry points／摘要 | source-only 可獨立運作；新 index work 限 exact published current |
| Static Relations | opt-in Symbol Solver、核准 roots/JARs、靜態目標、uncertainty、候選 framework evidence | 不是 runtime call graph，也不自動建立跨 repo joins |
| Source 讀檔 | 有界兩層預覽、多段／檔尾原文、Java 結構摘要、原始行號與精確 locator | 省略本文不算已讀；cursor 固定版本／view，不跨程序持久化 |
| Source 檔名探索 | `find_files` metadata 分頁；與 `search_text.filePattern` 共用 bounded Unicode-scalar glob | 不讀本文、不增加 index 依賴；非完整 OMP／shell glob；exact pins、完整 inventory 驗證與共用 search permits |
| Repository 導覽 | 描述、module／入口／關係模板、短版 discovery hints | 文件一律 NOT_VERIFIED；入口、module 與已部署服務不是同一件事 |
| Document MCP | 獨立 stateless metadata 瀏覽／literal 搜尋／完整讀取；全域或可選 owner scope；每 business repo 一個 latest document group | mixed rows 帶各自完整 context；list/search cursor 不互換；A 更新不影響 unchanged B 的 read／scoped cursor |
| Local Git 文件操作 | 具名 update/remove/status、format-1 catalog、format-2 per-owner selection、owner map 原子發布 | 支援 final-file removal、same-operation recovery 與 empty map；無 remote／手填 JSON；Git/index/working files/selection 不是跨資源單一 transaction |
| Agent 操作入口 | manage／describe／query 三個 skills；query 包含陌生專案探索 | 操作、撰寫、查證各自獨立；有界概覽附 source 證據與未讀範圍，不將 source、文件與 index 強綁 |

### 模組與依賴

目前為四個 Maven modules：

- `semantic-indexer`：唯一 source/index writer，持有 Git、jobs、staging 與核准分析輸入。
- `semantic-query`：只讀 published source/index；不持有 Git credentials、不加入 parser 或 Indexer 私有儲存。
- `document-query`：獨立只讀文件 MCP；同一 executable 的明示 offline CLI 才能執行核准的 local 文件更新。
- `semantic-model`：只依賴 JDK 的 identity／publication contracts。

沒有 JDT LSP、服務端 LLM、目標專案執行、annotation processing 或自動依賴下載。Relations 的 local JAR 準備由具授權的外部操作者處理；不是 Indexer 下載／建置業務 repo 的功能。

## 現行文件工作流

1. Author 讀核准 exact source，依 [canonical template](operations/repository-context-prompt.md#module-aware-guide-template) 產生描述／導覽並列出已讀與未讀範圍。描述可記錄 module、repo 關係及可由 source 檢查的線索，不將關係當成 runtime wiring。
2. 文件集中於獨立 **local Git，無 remote**。每個 business repository 的具名 descriptions/guides 作為一個 publication group；format-2 selection map 將 owner 指向一個 effective document commit，generated catalog JSON 維持 format 1。Formal APPROVED update 須核准精確內容/source identities；named removal 須明確移除核准。TEST_ONLY/DRAFT 保持實際狀態。
3. [manage-repository](../skills/manage-repository/SKILL.md) 以 `update` 更新具名 Markdown，以 `remove` 移除具名檔案（包括最後一檔），以 `status` 檢查 Git HEAD candidate 與 owner map。只改文件不要求 business repo commit、source refresh 或 index build；`serviceVerified:false` 須另經實際 Document MCP 查證。
4. Query agent 無業務關鍵字時用 `list_documents` 瀏覽 metadata，有關鍵字時用 `search_documents`；未知目標可全域探索，已知 owner 可用 `repositoryId` 縮小範圍。每列 context 各自綁 owner 與完整 document SHA。Agent 讀完整文件，再透過 Source Query 的 bounded list／find／search／read 查證使用者選定的 source pin。A 更新不影響未變更 B 的讀取／scoped cursor；global cursor 在任何 effective map 變更後失效。

Source change／rollback 後可有 source/document lag。Author 依 resulting source SHA 修訂受影響的具名文件，並在 document Git 建立 forward commit；不 reverse-select 舊文件、不自動同步，也不因 related context fan-out 重寫其他 owner。不同 SHAs 本身不證明敘述矛盾；missing／removed documents 只觸發 source discovery，不表示 Source unregister/delete。

## Release 待驗收

下列是 release gates，不是已授權的執行任務：

- Docker image／CI 的實際建置與啟動驗證。
- 正式部署的 TLS／ingress、唯讀 mounts、分離 UID／tokens／allowlists、SQLite 私有 JNI temp 及檔案權限。
- 正式 repo 的資料量、尖峰 concurrency、磁碟／記憶體 headroom 與故障恢復容量；小型 fixture 或短時 RSS 取樣不能替代。
- 正式文件內容核准與發布。Local 文件初始檔案為 0600；跨 UID reader 需操作者配置。Linux extended-POSIX-ACL-only namespace 不支援 local CLI。

發布／重建／回退順序見 [Release and recovery](operations/source-mcp.md#release-and-recovery)。這些 Source 歷史／retention 規則不因文件導覽改變；不要重寫 sealed source/index 或以 reset／GC 掩蓋部分成功。

## 未啟動的方向

| 方向 | 狀態／前提 |
| --- | --- |
| Elasticsearch 文件搜尋 | 未實作、未排程；先以現有 local Git／literal search 使用情況判斷是否需要。Git 原稿仍是唯一來源 |
| 自動跨 repo projection／graph（原 Phase 5） | 暫緩；目前採文件導覽 → repo 關係線索 → exact source 查證，不把候選連線當 runtime wiring |
| 向量搜尋、排程重產、remote 文件同步、publisher HTTP/MCP API | 不在目前已交付範圍，沒有默認啟用或實作承諾 |

下一個 milestone 應按實際使用缺口另行評估；不能把舊 roadmap 的實作順序或歷史驗收清單當成待執行授權。

## 驗收與歷史

- 舊版 Source／index／Document results 只適用於其記錄的 code、inputs、runtime 與 scenarios。各 milestone 的 ordinary、native、Query 證據及限制集中於 testing；未執行的 image／CI／部署 gates 仍待獨立授權與驗收。
- 過往 Module-aware Guide 的引用失敗、歷史 source 越界及其他被拒答案仍是失敗。後續修正／新案例的 PASS 或 PASS_WITH_RECOVERY 不回溯改寫原判定，也不代表所有模型回答都可靠。
- 過往 Phase 1–4、導覽與 Document MCP 的逐輪紀錄可從 Git 歷史取得；整理前完整文件保存在 `527ccb8`。本機 plans／完整 traces／原始答案仍在未追蹤的 `docs/superpowers/` 與 `.superpowers/`，不將私有證據搬入團隊文件。
- `AGENTS.md`、README、operations、OpenAPI／MCP schemas、Dockerfiles 與 CI 必須維持相同契約；更新文字或建置設定不等於執行了相應 release gate。
