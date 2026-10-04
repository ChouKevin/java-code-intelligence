# Source MCP Agent 接受測試

本文件區分 native 測試、真實獨立程序／容器、實際 OMP/Codex/Claude 模型客戶端與部署安全。**不能**以其中一層代替另一層；Source MCP 回傳可追溯來源，不產生已驗證業務推論或 finding。Phase 1 沒有 semantic index（`semanticStatus: NOT_READY`）、舊 review/diff/fact tools、Mongo、JDT LS 或服務端模型。

## 入口與測試範圍

參閱 [Testing and verification](testing.md#verification-commands) 取得完整命令與先決條件。普通 reactor `mvn --batch-mode --no-transfer-progress test` 不需 Mongo/Docker/JDT；`SOURCE_TEST_RG=/absolute/path/to/rg scripts/test-source-mcp.sh` 使用真實 Git、ripgrep、native MCP SDK、分離的 Indexer 與 warm/cold Query JVM；另外先建立兩個 source image 再執行 `QUERY_IMAGE=java-source-query:phase1 INDEXER_IMAGE=java-source-indexer:phase1 scripts/test-source-images.sh`。腳本成功不是已驗證的模型使用、遠端 TLS、私有 repo coverage 或吞吐量。

兩個 `/mcp` server 都透過 `X-Api-Token` 認證，但權限**分開**：Indexer 私有入口使用管理 token、只有 `prepare_source` 和 `get_job`；Query 只讀入口使用 read token、只有 `list_repositories`、`get_context`、`list_files`、`search_text`、`read_source`。HTTP 與 MCP 應對同一 facade 的成功及安全錯誤比對 JSON TextContent、structuredContent、HTTP body；不能只確認 schema、工具數或連線成功。客戶端以隔離設定注入 secret，不修改全域登入或儲存 populated secret。私有 Indexer ingress 與 Query ingress 的正式部署須有核准 TLS；localhost HTTP 無法證明此項。

## Source journey 驗收

1. Indexer 配置核准 repo ID、Git URL、固定 default branch、顯示名稱及可選 guide 路徑；Query 明列 `semantic.query.source.allowed-repositories`。啟動先確認 `list_repositories` 為 `NOT_PREPARED`、`get_context` 無可讀 identity；註冊不做 fetch。
2. 在送出前**持久保存** canonical lowercase UUID requestId；呼叫私有 `prepare_source(repositoryId, requestId, revision?)`。可選 revision 只接受完整 40 位 lowercase SHA。接受後用**原 requestId** 呼叫 `get_job`，核對原 jobId、同 repo、解析 SHA、`COMPLETE`，即使接受回應遺失也不另建工作。GET 只帶 jobId 或 requestId 二選一；`REQUEST_NOT_FOUND` 不授權自動重送。明確 terminal failure 且人工修正後的新請求使用新 UUID。
3. `get_context` 以 top-level `repositoryId`、可選 exact `revision` 發現 READY；把回傳的 `{repositoryId,revision}` 原樣放在每個 `list_files`、`search_text`、`read_source` 的 nested `context`。不把 branch、CURRENT/REVIEW selector、fact ID 或 pathname 當 context。list_files 的 direct-child cursor 保留相同 repo/SHA/directory/filter/window；read_source 以原 window/cursor 接續 UTF-8、CRLF、超長行及 EOF，不能猜下一頁的行號。search_text 只接受 literal、單行、大小寫敏感查詢，沒有 cursor；`truncated=true,scanComplete=false` 要縮小 directory/glob，不是假裝搜尋完成。
4. 在受控 remote 發布 B，核對新 discovery 指向 B、之前保存的 A context 仍取回 A bytes；指定 A 的 get_context 仍 READY。停止 Indexer 並使 disposable remote 不可用，冷啟只掛 published 子目錄的 Query，再確認 A/B 的 HTTP/MCP list/search/read 和禁用 admin mutation/private credentials。Indexer 失敗準備 B 時 A 仍 READY；不靠 staging/orphan 目錄推斷 publication。
5. Guide 缺少、無效或只是普通 Markdown 都不妨礙 source；`NOT_VERIFIED` 與 navigation hint 不轉為 code relation 或已核實業務敘述。清楚檢查被排除的 `.git`/`target`/`build`/`.gradle`/`node_modules`/`generated`/`*.class`，及 symlink、binary、unsupported encoding/path、submodule、LFS pointer 的不支援狀態。未授權／未準備／未知 SHA／timeout／busy 不得洩漏絕對路徑、token 或 source bytes。
6. 外部模型客戶端獨立紀錄實際版本、endpoint discovery、實際 tool calls、repo/SHA/path/line、payload bytes/time 和觀測到的限制。來源宣告、呼叫文字匹配、runtime 是否啟用、業務流程結論各自標註證據；repository 文件與 guide 是不可信輸入，不可當指令執行。沒有命中不證明不存在。若模型登入/OAuth 失敗，標示「未驗證」，不得以 SDK 結果代替。

## 已觀察及尚未完成（2026-10-04）

控制者已觀察最終 Source-only reactor **84 PASS**（Model 6、Indexer 29、Query 49；0 failures/errors/skips、無 compiler warnings）和修正後 clean `scripts/test-source-mcp.sh` PASS，使用實際 Git/rg、native SDK 與獨立 Indexer/warm Query/cold Query。fixture `video` A SHA `395566e8f884883d147020d42c21b4b281185a41`、B SHA `c8acbff5042091b9f36c8d4eb2b3296b3e4f82c7`，出自 disposable run `/tmp/source-mcp-journey.Qhy1JQFv/artifacts/revisions.json`；同目錄實際 response/provenance JSON 逐步引用 repo/SHA/path/line，且每一步與 exact Git blob 比對，**不是** server 已推出 verified flow。B 與 known-SHA republish 不改 A bytes/metadata；Indexer/remote/private paths 不可用時 cold Query 仍可讀 A/B。最終兩個映像 build 與 `scripts/test-source-images.sh` PASS：真實 Git/read/search、UID 10001/10002、Query 唯讀 published child、無 private mount/token、nondefault root/rg 環境設定及 invalid-rg 啟動拒絕。另有隔離式真實 ENOSPC、拒絕舊權限前不開 HTTP、反向 audit clock 的 durable job 復原、15,000-file／1,000-untracked-prefix bounded search 與 JDK FileRead 一次完整 inventory 驗證證據；只清除本次自建 containers/tmpfs，未清除既有資料。raw Git/process logs 不保留，只有刻意的 JSON source evidence。完整命令證據見 [testing](testing.md)；whole-branch review 四項原 findings 與兩項殘餘 findings 全部 CLOSED，production checkpoint `bc1bd3d` 的 final dependent review 無 findings，控制者接受 Phase 1 實作。Reviewer 未執行 checks；main 整合及外部環境 release checks 仍獨立。此段是在觀察上述結果後更新，未用文件內容替代執行證據。

尚無本 release 的實際 OMP/Codex/Claude 模型操作紀錄、私有 repo 接受、遠端 TLS 或 50-user 容量證據。舊 schema-4 semantic/review 模型紀錄與舊 Mongo cold-read PASS 不可重新命名為 Source MCP PASS。任何公開 fixture 結果只說明該 fixture 被檢查的 repo/SHA/路徑/行號；外部 prompt 見 [repository context prompt](repository-context-prompt.md)，部署／失敗恢復見 [Source MCP operations](source-mcp.md)。
