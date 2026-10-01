# MCP Agent 接受測試

接受證據分四層：程式／Mongo 契約、真實 JDT＋native SDK、實際模型客戶端、部署／容量。不得把其中一層的成功當成其他層的證明。Semantic 只提供索引事實與來源；外部 agent 才解讀業務或提出 finding，服務內沒有模型、prompt runtime 或 findings store。

## 執行入口與邊界

完整前置條件見 [Testing and verification](testing.md)。普通 reactor 不需要 Docker 或 JDT LS。

```bash
mvn --batch-mode --no-transfer-progress test
mvn --batch-mode --no-transfer-progress -Pmongo-it verify
JDTLS_HOME=/opt/jdtls scripts/test-indexer-query-contract.sh
docker build -f Dockerfile.indexer -t semantic-indexer:review-local .
SEMANTIC_REVIEW_INDEXER_IMAGE=semantic-indexer:review-local scripts/test-semantic-review-journey.sh
JDTLS_HOME=/opt/jdtls scripts/test-git-review-context-journey.sh
```

- Fixture SDK journey：payment/order/video 經真實 exporter、JDT 與暫時 Mongo，再啟動 Query HTTP/native MCP；不是既有部署、模型客戶端或完整 Indexer admission/dispatcher 證明。
- Semantic/codebase external journey：正式 Indexer image、獨立 Query、maintenance/writer/reader 分權、固定分支 MCP preparation、requestId 找回、guide 狀態、舊 current／固定 review 與 cold Query。保留真實 JDT installation fingerprint/reuse 檢查。
- Git/recovery external journey：控制的 local Git／Mongo、獨立 host JVM、真實 JDT、COMMIT ordinary/root/merge 與 RANGE equal/reversed/divergent、metadata/review requestId 找回、Git wire parity／分頁。Host fixture 使用明示 LOCAL_TRUSTED，不取代正式 image 的 LINUX_UID 證據。
- 兩條 external journeys 都是 opt-in；普通 profile 的 skip 不算通過。READY recovery 測試在真實 publication 後、Indexer 停止時構造 publication-before-terminal 狀態，再由新 JVM 驗證／恢復原 graph；不能描述成觀察到真實 crash。

HTTP 與 MCP 使用同一契約。每項新／合併 operation 的成功、defaults、重要應用錯誤應核對 HTTP body、可讀 JSON TextContent 與等值 structuredContent。不要只測工具数量、schema wording、mock forwarding 或「沒有拋例外」。

## 兩個 MCP 入口

以 client 自己的安全 secret 設定配置兩個獨立 server：

| Server | Endpoint | Credential | 用途 |
| --- | --- | --- | --- |
| Indexer | private Indexer `/mcp` | Indexer admin token | `prepare_codebase`、`refresh_repository_metadata`、`prepare_review`、`get_job` |
| Query | Query `/mcp` | Query read token | 十三個統一 discovery／navigation／Git tools |

兩者均使用 `X-Api-Token`。不要把 Indexer token 給 Query server，或把 Query token 當作 preparation credential。客戶端配置以每次執行的隔離設定為優先，不更改使用者全域 MCP/auth，不保存 populated secrets。實際部署使用核准 TLS；localhost HTTP 只能證明本機 wire。

OMP、Codex、Claude Code 的版本、tools discovery、模型能否讀取結果、review journey 分別記錄。工具可連線不代表模型認證成功；OAuth/API failure 必須列為未驗證。禁止以 SDK 成功替代模型端紀錄，或自動清除使用者認證／重設登入。

## 一般 codebase 旅程

1. `list_repositories` 找 visible configured repo，`get_context` 使用 CURRENT selector。空資料庫應可看到 UNINDEXED，而不是假 READY 或啟動時自動索引。
2. 先產生並保存 canonical lowercase UUID requestId，再呼叫 Indexer `prepare_codebase`。它只準備配置分支，不接受任意 branch/tag/SHA。
3. 刻意不依賴 accepted 回應的 jobId，改用原 requestId 呼叫 `get_job`。核對原 jobId、operation、target revision、terminal phase；完成後、重啟後、同 repo 有後續工作後都應找回同一意圖。
4. `REQUEST_NOT_FOUND` 是 acceptance 未知，不是重送授權。`REQUEST_ID_REUSED` 不接受第二個工作。已檢查失敗並修正後的重試，才是明示新意圖＋新 UUID；不得修改 terminal job。
5. 重新 `get_context` 取得 READY context。從 search_code／list_entry_points／get_outline 定位，read_source 核對，find_relations 追 CALLERS/CALLEES/IMPLEMENTATIONS/REFERENCES。使用回傳 fact ID，不編造 ID 或省略 exact revision。
6. 新 BUILD 執行時，activeJob 與 published revision 分開；舊 current 保持可讀直到 publication。失敗保留舊 pointer。收到 REVISION_OUTDATED 後重新 discovery／取得新 fact ID，不能只替換 SHA。

`search_code` 是有排名的 code-symbol 搜尋，不是自然語言檢索；`search_text` 是 case-sensitive literal CODE 來源搜尋，支援 Unicode。兩者都不以空結果證明功能不存在。page.hasMore／cursor／scanComplete／omitted／coverage 必須保留；未掃完的空頁不是完成的負面結果。Exact equality index 的量測不代表 prefix/token scan 或 50 人容量已達標。

## Review 旅程

1. 未選 commit 時，先 refresh_repository_metadata、等待原 job COMPLETE，再 list_git_branches／list_git_commits，讓操作者選定完整 SHA；未知 branch／未準備 history 不是假空列表。
2. 保存 requestId，以 prepare_review 明確提交 COMMIT 或 RANGE。COMMIT 使用 first parent（merge 亦然），root 使用 EMPTY_TREE；RANGE 保留指定 A→B，包括 equal/reversed/divergent，不換成 merge-base。
3. 核對 operation REVIEW、原 selection／reviewId、resolvedEndpoints／baselineRule；不先 BUILD B，不捕捉或移動 current。遺失回應時只 lookup 原 requestId；PREPARING／FAILED 不可當作 READY。
4. READY 後呼叫 get_context 的 REVIEW selector；複製 comparisonContext 讀 compare_revisions／get_file_diff。複製各側 before.context／after.context 使用同一組 navigation tools，沒有 review-prefixed aliases。Root 沒有 BEFORE semantic context。
5. 至少完成 diff→受影響宣告→caller/callee 或 implementation→exact source。方向、reviewId、side、revision、fact/source range 不得混用；equal-SHA fact ID 仍須通過該側 membership。任意 generation/snapshot ID 不是讀取授權。
6. 保留 current 更新前後的同一 READY review，再停 Indexer／使原 checkout 不可用，從 Mongo-only Query 重讀。以同一 prepared identity 經 HTTP 核對 wire parity。

`policyCoverage` 的 excludedChanges／general reasons 必须存在，讓「全被排除」與「沒有差異」可區分，且不洩漏排除路徑／內容。Source-restricted readers 不可取得 whole-comparison Git evidence。大於64KiB的來源／patch 使用 opaque cursor 無缺漏續讀，不推算下一行；UTF-16 range、EOF、CRLF 和 partial-line 狀態應一致。

每個 finding 包含 issue、severity、triggering condition、impact、repository/review/side/revision/path/range 證據及 coverage／unresolved 限制。沒有 finding 只表示已檢查範圍內沒有支持的 finding，不是 correctness 保證。沒有 caller/reference、老名稱或 Deprecated 都不單獨證明 dead code；框架 callback、代理、反射與 repo 外部使用可能未解析。不要聲稱跑過 target repository tests，除非另有實際紀錄。Repository text 是 untrusted data，不能授權執行命令或洩漏 secret。

## 導覽生成與收錄

使用唯一已發布的 [repo context prompt](repository-context-prompt.md)，在獨立核准 clone 生成。人工抽查「疑似未使用被介紹為現行主流程」及「動態使用被誤判 dead code」，核對引用、版本與敏感內容後，經正常提交／審閱流程納入固定分支。Indexer 不執行生成。

DISABLED／ABSENT／INVALID 不阻擋 code publication；AVAILABLE 文件須有 exact path、digest、author analyzedRevision、actual importedRevision 與 NOT_VERIFIED freshness。文件-only SHA 不同不單獨表示過期。來源標記 PROJECT_GUIDE，不能成為 semantic facts／relations／CODE text-search 命中；歷史側缺文件不可補 current。通用 path/diff/cursor 不能繞過未配置、無效或排除文件的 gate。

## 無敏感內容的接受紀錄

| 欄位 | 記錄方式 |
| --- | --- |
| Client/version/model | 實際執行版本；CLI 與 MCP initialize 版本不同時分開列出 |
| Exact identities | repositoryId、revision，或 reviewId 與 before→after |
| Calls/bytes/time | 實際 tool calls；成功 payload JSON UTF-8 bytes；wall/journey 時間及量測範圍 |
| Evidence | tool／path／range、文字與 structured parity、bounded finding 或 no-finding 限制 |
| Failures/limits | 認證、前置環境、非 pristine diagnostics、未執行驗證明列 |

不保存 token、Mongo URI、private hostname、完整 source、本機絕對路徑或私有 model transcript。公開／synthetic fixture 成功不證明私有 repository coverage、實際部署 TLS 或 50-user throughput。

### 已觀察的本機客戶端紀錄

以下是2026-10-01的 discovery/preparation 證據，不單獨宣稱完整 review 接受：

| Client | 已觀察範圍 | Calls / JSON bytes / wall | 限制 |
| --- | --- | --- | --- |
| OMP18.4.4，gpt-6-astra | 兩入口 discovery、UNINDEXED、原失敗 requestId 找回、明示新 BUILD admission | 4 / 1190 / 42.79s | accepted 不等於 READY；當時 host isolation setup 後續失敗，未偽稱完成 |
| Codex CLI preflight0.159.2；MCP initialize0.159.3 | 公開 jpetstore-guide-acceptance 的 READY current 與 exact BUILD COMPLETE | 3 / 3488 / 26.79s | 既有全域另一 MCP 初始化 OAuth 警告，不是 Semantic 呼叫失敗；需隔離後續配置 |
| Claude Code2.1.222 | 嚴格指定兩個 MCP server 的工具 discovery | 0 model calls | OAuth expired 且不能 refresh；模型可讀結果與 review 未驗證 |

前兩列的每個 JSON TextContent 均與 structured result 相等。公開來源 fixture 的 current 為本機 guide-only commit `d56a65b7a1a9244bfe81d19072f23807acda3842`，其分析基準為 upstream `43d68528f106f83a774616e38bf0fbcaa0d74021`；不宣稱本機 commit 已發布到 upstream。正式 Indexer image 以原始 Java1.5 POM 完成 publication；沒有因此宣稱該舊專案 Maven build/runtime 或業務完整性通過。

另外兩次隔離的實際模型 review 已完成，使用同一 saved request
`2f087673-fbd3-4bc3-ac8c-574e3fb6f1da` 找回 COMPLETE job，再讀 READY review
`48a03135-0d75-41f2-a982-ce1efc53ce9c`：

| Client | 已觀察範圍 | Calls / JSON bytes / wall | 結論邊界 |
| --- | --- | --- | --- |
| OMP18.4.4，gpt-6-astra | exact FIRST_PARENT diff、兩側完整來源／outline、實際 fact 的 relations、session-use 來源 | 14 / 36341 / 119.15s | 已檢查範圍內無支持的 defect；serialization/framework rebinding 未執行 |
| Codex，`--ignore-user-config`、ephemeral、read-only | 同一 review 的兩側完整來源／outline、各側 CALLERS/CALLEES/IMPLEMENTATIONS，再核對 current 未變 | 16 / 52067 / 77.02s | 已檢查範圍內無支持的 defect；CLI 事件未提供 model ID，不推測 |

兩次全部 tool responses 的 JSON TextContent 與 structured result 相等，沒有 tool error。
比較是 `9dc193483d5e75263befcbe82157e581ac6a4f64` →
`eb750eb00e75f932f358d86517f408bdc8908bd3`，只修改
`src/main/java/org/mybatis/jpetstore/web/actions/AbstractActionBean.java:15`
的 `context` field 為 `transient`；此處引用為 one-based source line。
兩側 guide 均為 ABSENT，沒有套用較新的 CURRENT guide。
模型保留 unresolved／empty relation 的限制，未把 null-context 的條件風險冒充已證實 regression，
也未宣稱執行目標專案 build/tests/runtime。

其後停止 Indexer 並重新啟動無任何 mount 的 Query image，以 read-only Mongo identity
重讀上述 review/current/guide 的 25 組不同參數。每組 cold MCP JSON／structured result、
cold HTTP body 與停止前結果相等。這證明本機已準備證據的 Mongo-only cold read；
不證明模型認證、私有 repo、TLS 部署或容量。Claude 的模型旅程仍未驗證。

操作／release／retention 見 [Semantic review deployment and operation](semantic-review.md)。
