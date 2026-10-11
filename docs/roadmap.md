# Java Code Intelligence — Roadmap

## 目前狀態

Source／Java index／Relations、獨立 Document MCP，以及 local Git 文件更新已完成本機實作與驗證。Local repository operations 已提交並合併到 `main`（`527ccb8`）。**本機完成不代表 release 已驗收**；目前沒有新增已核准的功能 milestone。

這份 roadmap 只保留目前交付範圍、待驗收事項及尚未啟動的方向。操作步驟以 [Source operations](operations/source-mcp.md) 與 [Document operations](operations/document-mcp.md) 為準；測試命令與實際結果集中於 [testing](operations/testing.md#current-verification-status)。

## 已交付能力

| 範圍 | 現有能力 | 主要邊界 |
| --- | --- | --- |
| Source-first MCP | 註冊、durable preparation、exact Git blob publication、list/search/read | Query 不觸發 preparation；註冊不等於 READY |
| 多版本與運行管理 | current／歷史 SHA 同時讀取、retention、read guards、容量 admission、恢復 | current 只由明示 default-branch refresh 更新；容量檢查不是硬 quota |
| Java syntax／folds | JavaParser 3.28.2、Java 21、sealed SQLite generations、syntax search／outline／entry points／摘要 | source-only 可獨立運作；新 index work 限 exact published current |
| Static Relations | opt-in Symbol Solver、核准 roots/JARs、靜態目標、uncertainty、候選 framework evidence | 不是 runtime call graph，也不自動建立跨 repo joins |
| Source 讀檔 | 有界兩層預覽、多段／檔尾原文、Java 結構摘要、原始行號與精確 locator | 省略本文不算已讀；cursor 固定版本／view，不跨程序持久化 |
| Repository 導覽 | 描述、module／入口／關係模板、短版 discovery hints | 文件一律 NOT_VERIFIED；入口、module 與已部署服務不是同一件事 |
| Document MCP | 獨立 stateless 文件搜尋／完整讀取、active／retained 文件版本 | 與 Source Query、source SHA、token 各自獨立；無命中須回 source 擴大查找 |
| Local Git 文件操作 | 具名 update、自動 catalog/digests、local commit、activate/status、明示 withdraw | 不需 remote／手填 JSON；不覆寫他人修改；本機成功另驗 running MCP |
| Agent 操作入口 | manage／describe／query 三個 skills | 操作、撰寫、查證各自獨立，不將 source、文件與 index 強綁 |

### 模組與依賴

目前為四個 Maven modules：

- `semantic-indexer`：唯一 source/index writer，持有 Git、jobs、staging 與核准分析輸入。
- `semantic-query`：只讀 published source/index；不持有 Git credentials、不加入 parser 或 Indexer 私有儲存。
- `document-query`：獨立只讀文件 MCP；同一 executable 的明示 offline CLI 才能執行核准的 local 文件更新。
- `semantic-model`：只依賴 JDK 的 identity／publication contracts。

沒有 JDT LSP、服務端 LLM、目標專案執行、annotation processing 或自動依賴下載。Relations 的 local JAR 準備由具授權的外部操作者處理；不是 Indexer 下載／建置業務 repo 的功能。

## 現行文件工作流

1. Author 讀核准的 exact source，依 [canonical template](operations/repository-context-prompt.md#module-aware-guide-template) 產生描述／導覽並列出已讀與未讀範圍。
2. 文件集中於獨立 **local Git，無 remote**。正式 APPROVED 更新須審閱精確內容／source identities，並另獲啟用授權；明示核准的測試可使用 TEST_ONLY／DRAFT。
3. [manage-repository](../skills/manage-repository/SKILL.md) 使用 local CLI 更新具名 Markdown；工具建立 catalog、commit 與 selection。只改文件不要求 business repo commit、source refresh 或 index build。
4. Query agent 先用 Document MCP 搜尋並讀完整文件，再用 Source Query MCP 查證使用者指定版本。先完整列舉可讀 repo，再依導覽決定深入順序；文件沒有命中不等於不存在。

Source SHA、文件 commit 與 index generation 是不同身分。文件不為每個 source commit 重產；讀歷史 source 時仍固定該 SHA，不因較新的導覽而改查 current。多 module／跨 repo 關係目前以文件線索加雙側 source 證據處理，不增加第二套 graph 或 catalog 管理介面。

## Release 待驗收

下列是尚未完成的 release gates，不是缺少對應功能，也不是已授權的執行任務：

- Docker image／CI 的實際建置與啟動驗證。
- 正式部署的 TLS／ingress、唯讀 mounts、分離 UID／tokens／allowlists、SQLite 私有 JNI temp 及檔案權限。
- 正式 repo 的資料量、尖峰 concurrency、磁碟／記憶體 headroom 與故障恢復容量；小型 fixture 或短時 RSS 取樣不能替代。
- 正式文件內容核准及啟用。Local 文件初始檔案為 0600；跨 UID reader 需操作者配置。Linux extended-POSIX-ACL-only namespace 不支援 local CLI。
- 依實際部署需求補充複雜多模組依賴、私有套件與未實測的 Relations framework artifact 情境；不宣稱所有 repo 都能自動 onboarding 成功。

發布／重建／回退順序見 [Release and recovery](operations/source-mcp.md#release-and-recovery)。失敗保留既有 READY；不要重寫 sealed source/index 或以 reset／GC 掩蓋部分成功。已回收 source 的恢復仍要求核准 origin 可提供相同 SHA。

## 未啟動的方向

| 方向 | 狀態／前提 |
| --- | --- |
| Elasticsearch 文件搜尋 | 未實作、未排程；先以現有 local Git／literal search 使用情況判斷是否需要。Git 原稿仍是唯一來源 |
| 自動跨 repo projection／graph（原 Phase 5） | 暫緩；目前採文件導覽 → repo 關係線索 → exact source 查證，不把候選連線當 runtime wiring |
| 向量搜尋、排程重產、remote 文件同步、publisher HTTP/MCP API | 不在目前已交付範圍，沒有默認啟用或實作承諾 |

下一個 milestone 應按實際使用缺口另行評估；不能把舊 roadmap 的實作順序或歷史驗收清單當成待執行授權。

## 驗收與歷史

- 目前操作規則、命令及最新本機結果見 [testing](operations/testing.md)；獨立模型查證門檻見 [agent acceptance](operations/mcp-agent-acceptance.md)。需要 agent 時明選 `gpt-6-luna`，不默換模型。
- 原 Module-aware Guide 的引用失敗、歷史 source 越界及其他被拒答案仍是失敗。後續修正／新案例的 PASS 或 PASS_WITH_RECOVERY 不回溯改寫原判定，也不代表所有模型回答都可靠。
- 過往 Phase 1–4、導覽與 Document MCP 的逐輪紀錄可從 Git 歷史取得；整理前完整文件保存在 `527ccb8`。本機 plans／完整 traces／原始答案仍在未追蹤的 `docs/superpowers/` 與 `.superpowers/`，不將私有證據搬入團隊文件。
- `AGENTS.md`、README、operations、OpenAPI／MCP schemas、Dockerfiles 與 CI 必須維持相同契約；更新文字或建置設定不等於執行了相應 release gate。
