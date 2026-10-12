# Source／index／document MCP Agent 接受測試

本文件是可重用的接受測試 checklist，區分 ordinary/native、獨立 packaged
process、真實外部模型客戶端、image/CI 與部署安全；任何一層不能代替另一層。
MCP 提供 source、syntax 與可選 static Relations 證據，不產生已驗證業務推論、
runtime call graph 或 finding。最新範圍與限制見
[Current verification status](testing.md#current-verification-status)。
Existing dated runs, fixture SHAs, and failures are historical baseline evidence
from their recorded code/runtime and do not establish latest-document-navigation
acceptance. Private `.superpowers/` evidence remains unchanged; do not rewrite
old answers or relabel historical results as new passes.

## 入口與測試範圍

- 從 [verification commands](testing.md#verification-commands) 選最小相關 lane，
  依 [resource safeguards](testing.md#resource-and-hostdata-safeguards) 授權、串行、
  限額執行。Written scenarios、連線成功與工具數不是執行證據。
- **Source Query** 不使用 Git、Mongo、JavaParser 或服務端模型，只讀 published
  source/index。**Document Query** 使用獨立 operator-managed central Git catalog；
  不能把 Source Query 的 no-Git 規則錯套到 Document Query。
- Indexer `/mcp` 使用 private admin token，提供 `prepare_source`、
  `prepare_semantic_index`、`get_job`。Source Query `/mcp` 使用 read token，提供
  `list_repositories`、`get_context`、`list_files`、`find_files`、`search_text`、
  `read_source`、`search_code`、`get_outline`、`list_entry_points`、`find_relations`。
  Document MCP 使用自己的 read token，提供 `list_documents`、`search_documents`、`read_document`。
- 比對實際 HTTP body、MCP structuredContent 與 model-visible text 的成功／安全錯誤。
  一般 JSON text 應與 structured result 一致；成功 `read_source` 的 numbered display
  不是 JSON，不可拿 display 替代 raw structured bytes/digests。
- Source Query clients 各自 initialize Streamable HTTP session，保存 server-issued
  `Mcp-Session-Id`；後續帶 session ID、協商的 `MCP-Protocol-Version` 與 read token，
  結束 DELETE session。兩個 clients 使用相同 JSON-RPC ID 都應成功，owner cancellation
  不影響另一 session，子程序被回收且後續 search 可用。見
  [session lifecycle](source-mcp.md#mcp-session-lifecycle)。Document MCP 是 stateless，
  不重用 Source Query session。
- Secret 只經隔離 client 設定注入，不改全域登入、不提交 populated secrets。
  localhost HTTP 不證明正式 admin/read ingress 的 TLS、UID/mount 或私有 repo access。

## Source journey 驗收

1. 以核准 ID/origin/default branch 配置 Indexer，Query 明列 allowlist。
   註冊不 fetch；`NOT_PREPARED` 不提供 readable context，orphan/staging 不算 publication。
2. 送出前持久保存 canonical lowercase UUID requestId。省略 revision 的
   `prepare_source` 才 refresh default branch 並原子更新 current；完整 40 位 lowercase
   SHA 只發布／重用 exact membership，不能提升 current，即使等於 current。
   用原 requestId 或 jobId（二選一）查 `get_job`，核對 operation、repo、job、resolved SHA
   與 `phase: COMPLETE`。回應遺失不自動重送；`REQUEST_NOT_FOUND` 不授權 resubmit。
   明確 terminal failure 且人工修正後，才以新保存 UUID 發新請求。
3. `get_context` 是 discovery-only。將 `{repositoryId,revision}` 原樣傳入 navigation
   的 nested `context`；branch、CURRENT selector、path 或 document commit 都不是 source
   context。每個 repo 有自己的完整 exact SHA，不能製造跨 repo 的共同 SHA。
4. `list_files` cursor 維持 repo/SHA/directory/depth/limit；depth-2 preorder preview 的
   `childPreview: ELIDED` 要另外列該 directory，與 page continuation 不同。
   `read_source` 保留 selection/view/maxLines/context/indexGeneration，沿 cursor 讀到完結，
   不猜下一頁行號。重啟後 cursor 明確失效；以同 exact context／retained generation 重開，
   不移除 pin。`search_text` 是 literal、單行、case-sensitive、無 cursor；
   `truncated=true,scanComplete=false` 要縮小 scope，不能宣称完整搜尋。
5. 驗證 `auto` summary、explicit raw ranges、多段 ranges、tail、UTF-8/CRLF、超長行與 EOF。
   summary 的 ELISION 不是已讀 body；做業務判斷前用 raw 補讀。核对實際 text 的 quoted
   identity、original LF-numbered rows、partial-line/elision 與 page/provenance metadata，
   同時保留 structured raw bytes。Numbered display 不保證 final answer 引用正確。
6. 在 owned remote 發 B，明確 revision-less refresh；new discovery 為 B，saved A 仍讀 A。
   exact historical H 不改 current、不自動建 index。停止 Indexer／使 disposable remote
   不可用，cold Query 僅掛 published children 仍讀 A/B，且無 admin/private credentials。
   failed preparation 保留既有 READY，不靠 directory existence 判斷。
7. Guide 缺少／無效／普通 Markdown 不妨礙 source；NOT_VERIFIED 不成為 code relation。
   檢查 exclusions（`.git`、`target`、`build`、`.gradle`、`node_modules`、`generated`、
   `*.class`）、symlink/binary/encoding/path/submodule/LFS unsupported 狀態。
   未授權／未知 SHA／timeout／busy 錯誤不洩漏 token、absolute path 或 source bytes。

## 檔名／文件瀏覽與陌生專案探索

- Source `find_files` 與 `search_text.filePattern` 使用同一 glob corpus：root／nested、
  bare wildcard、literal path、零層 `**/`、巢狀 alternatives、classes／negation、
  Unicode supplementary scalar 與 literal metacharacters。Invalid/over-limit
  patterns 明確失敗；比較同一批 TEXT 檔案的實際 native search，不以 helper 自算答案。
- filename pages 綁 exact context、pattern、directory、limit；current 更新後 retained A
  仍完整列 A。UTF-8 ordering、完整 result byte budget、late corrupt inventory、
  非 TEXT status、excluded/untracked/symlink 與 timeout/cancel cleanup 都須有行為證據。
- 實際 Document MCP 無 query 的 `list_documents` 每文件一筆，global/scoped/empty
  結果及回傳 entry 的完整 read 可用。A 更新讓 global/A cursor 失效，B 不受影響；
  no-op、last-file removal、UPDATE_REJECTED、list/search token 互換與重啟要分別核對。
- 外部 Query-only `gpt-6-luna` 以中性問題探索 Java/Maven multi-module 專案：
  不預先提供答案、檔案路徑或入口清單。完成無 filter 的 repository catalog 分頁，
  依必要文件導航後，以 find/list/read 取得用途、modules、候選入口、設定／測試位置。
  非 Java 檔名／讀取也需實際命中；這不代表全語言語意理解。
- 明確指定 retained A 而 current 為 B 時，所有該 repo 的 Source calls/citations
  保持 A；文件仍為該 owner 最新有效版本。Missing/stale 文件回固定 source，
  不把文件 provenance 當查詢版本，也不擅自比較其他歷史 source。
- 初步概覽列出已讀／未讀及 UNKNOWN；要求完整清單才追完對應 source/document pages。
  關聯 repo 按需要深讀，不預設全展開；build dependency 不等於 runtime wiring。
  檢查每個 claim 的實際 raw 證據與完整 locator，不以工具數或最後一筆正確引用代替。

## Index 接受條件

1. Source READY 後以另一个已保存 UUID 呼叫 `prepare_semantic_index`；新 work 的 exact
   revision 必須等於當時 source current，否則 `INDEX_NOT_CURRENT`，不 fetch／換 SHA。
   Source/index 共用 per-repo active-job 與 requestId namespace。恢復 committed receipt
   不受後來 current 移動影響；核對 operation、publication receipt 與 generation。
2. Source readiness、index metadata readiness 與 native runtime health 分開。
   missing/incompatible/corrupt index 或 SQLite load failure 不破壞 source-only tools。
   用 fresh process 查 index-local failure 前後的 source；Indexer semantic failure 後仍可
   處理 source job。`get_context` 不 prepare，也不靠啟動 JDBC 假稱健康。
3. `search_code` 只找 declaration name，`match: exact|prefix`、literal/case-sensitive；
   body 用 `search_text`。outline 是單檔宣告/file outcome，entry points 是 syntax candidates，
   不代表 framework/runtime 啟用；不製造 resolved type、generated member 或 call graph。
4. 建 A1/B1；明確 refresh default branch 回 A 才建 A2，再 refresh 回 B。
   保存 A1/B1 的兩個 independent summary cursors 跨 rebuild 後仍固定原 generation。
   unpinned A、pinned A1、unpinned B 對應 A2/A1/B1，逐一對照 completed receipts。
   collected generation 明確失敗，不換 latest。Source 30 日保留不承諾 old index TTL。
5. 核對 repository/SHA/path/blobId/contentDigest、raw UTF-8 `[startByte,endByte)` 與 LF lines。
   Unicode escape raw spelling 可不同於 decoded name。`coverage.complete=false` 與
   `hasMore` 分開；empty partial result 不能證明 repo 無此功能。
6. 真實 SQLite／HTTP／MCP 檢查 whole-row byte continuation、first-row/metadata 過大時
   `INDEX_RESULT_TOO_LARGE`／HTTP 413、160 KiB application 與 512 KiB transport budgets。
   以執行中的 SQL 驗證 deadline/cancel、connection cleanup 和 borrowed source guards；
   pre-expired budget 或一般 SQLite lock 不替代 operation lifecycle proof。
7. Disposable roots／controlled clocks 驗證 selected-generation guards、durable source/
   companion handoffs、disabled retention、same-SHA republication fence 與 path/digest replacement
   拒絕。Source Query 只掛 source/index 唯讀 children；JNI extraction 是 process-private。

## Relations 接受條件

1. 顯式核准 source roots、ordered local JAR bytes/SHA-256 與 Java 21 identity，建立 profile 3；
   default-disabled syntax build 保持 profile 2。真實 bounded child JVM 建 SQLite，parent 唯一
   publish，READY/GO/EOF 與 guards/lease 持續到 actual reap。No target build、annotation
   processing、application classpath 或 downloads。
2. A current 時建 A，refresh B 再建 B；兩個 exact contexts/generations 可同時讀。
   A continuation 跨 B/rebuild 不换 pin；正常受保護 collection 後 explicit
   `INDEX_GENERATION_EXPIRED`，不切 current。停止 Indexer 的 cold Query 仍讀 retained A/B。
3. 核對 overload canonical signature、interface static target、platform/approved artifact
   provenance 與 missing-type safe reason。Occurrence/endpoint raw spans 匹配 exact Git。
   lambda、initializer、shadowed field、package qualifier 不製造 false caller/missing type。
4. Source-symbol 可雙向，external target incoming-only，FILE outgoing-only；每頁保留 exact
   context、canonical UUID generation、anchor、direction、filters、limit，讀到 `hasMore=false`。
   去重重複成功頁，數實際 filtered rows，不能以 generation-wide relationCoverage 當 CALL count。
5. HTTP/event/DB facts 永遠 CANDIDATE；literal/dynamic/placeholder/unknown overload 分清楚，
   不 route/topic/table join、不推 runtime dispatch。Non-Java FILE explicit UNSUPPORTED；
   profile 1/2 capability unavailable，不偽裝 empty success。
6. 真實 worker/SQLite 驗證 sealed publication integrity、whole-response budgets、frozen-input
   replacement/restart、deadline/cancel/parent EOF/reap 與 refusal preserves READY。
   Query guard/permit 不早於實際 SQL cleanup 釋放，所有程序/data 都屬 disposable scope。
7. External Query-only `gpt-6-luna` client 解釋 A/B static difference、interface/unresolved limits，
   至少一次 continuation 與 exact-context raw-source 補讀。實際 external-JAR gate 要讀到代表性
   resolved artifact target/signature/digest；JDK/source-only calls 不替代外部依賴證據。
   Missing artifacts、Kafka/Rabbit positives、large layouts 或 association limits 未驗證則明示。

## Repository business-discovery acceptance

1. `list_repositories` **不帶 nameFilter** 並讀完所有頁；只有 allowlist 可見。nameFilter 是 display
   name 篩選，不是 keyword discovery。HTTP/MCP whole items、160 KiB application／512 KiB
   transport boundaries 與 gap-free continuation 必須實測，oversized item 回 explicit 413。
2. Absent/DRAFT 不 expose hint。只有 exact summary/keywords/analyzedRevision 經 human approval
   與另行授權 external Indexer config/start 才發布 APPROVED hint；invalid status/oversize 拒絕更新，
   保留 registry。Local draft／skill／Query 不會 register、fetch、prepare 或 import metadata。
3. `NOT_PREPARED` 可有 hint 但無 readable source；unallowlisted hint 不洩漏。Valid hint update/removal
   改 registry digest、使舊 discovery cursors 失效，不改 source current/bytes/receipts/index。
4. 完整 catalog 不代表要 upfront 深讀／get_context 每個 repo。先查 document/hint-matched candidates，
   證據不足、矛盾或 cross-repo clues 時擴大，包含無／薄／誤導 hints 的 repo。每個 investigated repo
   獨立 pin；final answer 列全部 checked repo/SHA（包括 negative/no-hit），另列 unavailable/unread scope。
5. Hint analyzedRevision 不授權換 source。Historical request 保持每個 user-selected pin；缺 path
   用該 SHA list/search，不 prepare／切 current。Unequal SHAs 本身不證明 behavioral conflict。
6. Hostile hints／source comments 是資料不是指令。Neutral question 不 preselect candidate names／formula
   oracle；完整 tool trace 查 local-source/admin bypass。跨 repo 至少讀各端 visible source/data contract，
   matching names、HTTP paths 或 keywords 不證明部署 connection；無命中不證明不存在。
7. 涉及 module 時分 aggregator/application/library、build dependencies、實際 declared entry 與 deployed
   service；來源宣告、candidate relationship、runtime enabled 與 business inference 分開交代。

## Independent Document MCP acceptance

**Latest-document-navigation local acceptance is recorded in
[Testing and verification](testing.md#current-verification-status).**
Ordinary/native checks and fresh dual-MCP Query scenarios have executed; recovered
model/tool/citation errors remain PASS_WITH_RECOVERY, not clean passes. The
checklist below remains the contract for future changed snapshots and deployments.

Use [Document MCP operations](document-mcp.md) and the actual packaged native
launcher, separately from external-agent reasoning.

- Admit the complete bounded format-2 owner map and format-1 Git catalogs before
  replacing the reader state. Runtime checks cover last-good `UPDATE_REJECTED`,
  fail-closed startup, valid empty selection, resource/result/time budgets,
  `DOCUMENT_BUSY` ownership and leases through materialization.
- Global search can return hits from different owner commits. Verify each hit's
  complete `{documentRepositoryId,repositoryId,documentRevision}` context,
  lexical path/section ordering, and full-document read with that exact context.
  Optional `repositoryId` scope is successful and empty for a valid owner with no
  selected documents; no single top-level document commit represents a global page.
- Source cases preserve independently selected exact contexts: document source
  metadata never authorizes a Source SHA, and historical source requests stay on
  their chosen pin without historical-document lookup. Thin/no-hit/removed/stale
  guidance expands source discovery; it is not evidence of absence.
- Update A while B remains selected at its previous commit. Verify stale A read
  and A-scoped cursor fail; B read and B-scoped cursor remain valid, including a
  B hit returned before A changed. A global cursor expires only on an effective
  owner-map change; a true A no-op invalidates neither scope. A failed A
  candidate preserves the actual last-good map.
- Explicit named removal changes only that owner's map entry and Git tree in a
  new commit; final-file removal removes that owner, and final-owner removal
  leaves a valid empty map. Old A contexts fail while B remains readable. Git
  history remains but is not document MCP membership; no Source operation occurs.
- Selection-write failure recovery is inspected and repeats the same authorized
  update/remove with unchanged inputs, reusing its committed candidate without a
  duplicate empty commit. Cover a pending final-document removal and refuse
  arbitrary old-commit activation or a later changed-input candidate.
- Source advance/rollback may leave documents temporarily lagging. Revise the
  named current group against the resulting source SHA using a forward document
  commit; never reverse-select an old document commit or mutate Source current.
- Preserve full document citations
  `documentRepositoryId@fullDocumentSHA:path#L<sectionStartLine>` and exact source
  citations `repositoryId@fullSourceSHA:path:Lstart-Lend`. Source verification is
  mandatory even when document trust is `NOT_VERIFIED`.
- A fresh external `gpt-6-luna` client must receive only both read MCPs, a neutral
  question and selected source contexts. Preserve client/version/invocation,
  complete unedited arguments/results and source-grounded answer; no local-source,
  author trace, answer key or prepare credentials. Current recorded runs do not
  authorize deployment or replace acceptance for a later changed runtime.

## Centralized pregenerated navigation acceptance

This is the author/handoff/query contract; local named-document delivery is not
external Document MCP search or Elasticsearch proof. Follow
[central handoff](repository-context-prompt.md#central-document-handoff) and the
[query skill](../../skills/query-business-code/SKILL.md).

### Role isolation and exact inputs

1. Controller selects actual approved source revisions and read scope. Independent author receives neutral
   brief, exact tracked Git blobs/approved clone, allowed paths/types and draft destination, not previous
   answers/guides/oracle trace. Record read/unread scope, client/model/invocation, original output/citations.
   No dirty/untracked mixing, implicit fetch, target execution or business-repo write authority.
2. Audit identity/lines/semantic support, sensitive content, module distinctions and unknown endpoints.
   Preserve original/revised drafts separately as DRAFT/NOT_VERIFIED. Design approval is not approval
   of exact generated content. Formal updates need exact-content/analyzed-revision human review and
   separate publication authority; TEST_ONLY use requires explicit current fixture authorization and
   never becomes APPROVED. See [test-only authorization](repository-context-prompt.md#test_only-測試授權).
3. Query is fresh and independent: explicit `gpt-6-luna`, only authorized document input plus Source Query
   read MCP (or independent Document MCP for that lane), no author reasoning/source/oracle/admin tools.
   Local delivery records exact bytes, digest, byte length, input name and envelope; do not call it MCP.
4. Document repo/commit/path/section differs from analyzed business-source SHA and every cross-repo pin.
   Attach real document commit after commit, not a self-hash in Markdown. Uncommitted synthetic input
   has real name/digest, not fabricated commit or publication status. Keep approval/trust/publication separate.

### Required query cases

- Current generated navigation: full catalog, candidates first, independently pin investigated repos;
  read source descriptors/entries/rules and answer from source, not generated assertions.
- Historical: fresh session keeps selected exact versions; read documents as documents, not permission
  to query their analyzed source. Citation repair cannot expand source scope.
- Thin/omitted: independently label sparse input without rewriting original author output; expand to
  unhinted candidates as needed and report investigated/no-hit/unread scopes.
- Real discrepancy/unavailable side: quote exact document claim and pinned source; independently pin
  known cross-repo target, keep unknown target/runtime wiring unknown. Do not manufacture conflict.

### Handoff and answer audit

Inspect each delivered section's actual document identity/section, primary analyzed source, required
context/header/Evidence rows, cross-repo identities, qualifications and unread scope. Full reads preserve
the complete section—including tables, qualifiers and evidence-to-context associations—under the exact
owner-bound document context; a snippet alone is not a full read.
Human approval of exact content, a local candidate commit, owner-map publication/selection, running MCP
adoption and formal acceptance are distinct facts; never infer one from another. Use the classification
rules below; never replace model output with controller-corrected prose.

## Module-aware guide acceptance

Use the [module-aware template](repository-context-prompt.md#module-aware-guide-template) for reusable
source-only guide checks, not a requirement to commit centralized documents into business source.
Internal readers share access to onboarded repos/modules; no new differential ACL or graph API is implied.

Choose owned synthetic repositories with an aggregator, two applications/entries, shared library/build
references and a cross-repo HTTP candidate whose deployment base URL is unknown. Preserve a guide
analyzing a different endpoint version. With Indexer stopped, fresh Query-only Luna reads guides,
descriptors, entries, caller, endpoint and non-secret configuration at independent exact pins.

Current and separate historical cases distinguish guide/source route discrepancy, selected-version
path mismatch and unknown deployed connection. Unequal SHAs or guide-only commits alone are not
behavioral conflict. Do not infer sibling-module calls, generated members or absence from partial search.
Audit every source/guide identity, byte span, attached claim support and disputed raw range; preserve
actual numbered MCP text and structured content. No index is required for source-only guide acceptance.

## Agent-operated onboarding acceptance

Use [manage-repository](../../skills/manage-repository/SKILL.md) for operator work,
not the description-only or Query skill. Fresh operator receives approved origin/ID, disposable paths,
existing executables/artifact inputs and explicit authorization, not prewritten dependency settings.

1. Save source UUID before submission; revision-less refresh reaches `phase: COMPLETE` before deriving
   source roots/dependencies from that exact SHA. Preserve uncertain-outcome lookup rules.
2. For the external-JAR case, multiple approved cached versions and descriptor-selected version require
   demonstrated choice: coordinates/order/digest/roots. No downloads, wrappers, target build or global cache edits.
3. Authorized disposable config/restart loads frozen Relations inputs; use separate saved index UUID,
   exact current and COMPLETE receipt/profile/generation. Source-only does not gain index/admin scope;
   source/JDK-only Relations cannot satisfy an external-dependency gate.
4. Stop Indexer before separate Query-only Luna. Check actual source/descriptor/outline, external static
   target/signature/digest, full paging and exact citations. External library behavior needs implementation
   or contract evidence; resolved call signature alone does not prove API semantics.
5. Deliver description DRAFT/NOT_VERIFIED, not an APPROVED hint; report operator, query and deployment
   results separately. Unsupported layouts/effective-model/private downloads/large projects need own evidence.

### Scoped local repository operation acceptance

Record source-only add, default refresh, exact historical prepare, description-only, local TEST_ONLY
without remote and optional Relations routing distinctly. Text-only routing/links are not runtime proof.

Local-document operator gets approved normal disposable Git/selection paths,
actual Markdown/source identities and explicit named update/removal scope, not
catalog JSON or an expected sequence. Use the packaged local command, preserve
unrelated dirty bytes, keep TEST_ONLY/DRAFT/NOT_VERIFIED, and verify running
Document MCP identity. CLI commit/status alone may accurately be
`serviceVerified:false`; never claim adoption without wire proof. No invented
remote/push/source prepare/service restart.

After operator update, independent explicit Luna client has only Document and Source Query MCP read
tools, not local source, author trace, oracle/catalog-writing/management tools. It reads operator-produced
document and verifies independently pinned source. Preserve failed setup commands/recovery and all
raw outputs. Supported permissions must be exercised under actual reader conditions; privileged bypass
is not a permission-fault test. Unsupported Linux ACL-only namespaces remain explicit limitations.

## Citations, result classification and evidence

For all external-agent cases explicitly select `gpt-6-luna`, record client/version/invocation, requested
versus provider-reported identity, full MCP arguments/text/structured results and unedited answer.
Model unavailability is blocked, not permission to silently switch. Query-only isolation is distinct from
controller Git-byte audits; an observed no-bypass trace is not OS filesystem isolation.

| Dimension | Acceptance evidence |
| --- | --- |
| Identity/read validity | Full repo/SHA/path/original LF range and raw bytes, or full document identity/real section; cited passage was actually read at that pin. No shortened SHA/path, fabricated local link or retargeted revision. |
| Claim support | Neighboring passage supports the actual claim; guide/document-source comparison cites both sides; cross-repo conclusion cites every inspected side and states unavailable/runtime uncertainty. |
| Recovery | Original failed call/citation, explicit correction/withdrawal for the same claim, valid replacement and supporting read trace retained. Disputed ranges require exact-context raw-range verification. |

- `PASS`: scoped criteria met, valid supported claims and no recovered failure.
- `PASS_WITH_RECOVERY`: actual setup/tool retry or explicit same-claim correction; state which kind,
  preserve attempts and verify final pins/support. A recovered BUSY or INVALID_ARGUMENT is not a zero-error run.
- `FAIL`: uncorrected false citation, wrong revision, unsupported conclusion, skipped required pagination/read,
  controller-edited answer or self-reported recovery unsupported by trace. Later unrelated locator, whole-file
  citation hiding misplaced evidence or inline acknowledgment retaining the bad locator does not repair it.
- Withdrawn claim is narrowed/unknown, not proof of the original claim. Later fresh accepted run does not
  retroactively pass older failed answers. Blocked/unexecuted gate remains reported separately.

Inspect JSON-RPC error, tool `isError`, item status and structured error codes, not only outer `error:null`.
Citation syntax and semantic support are separate audits. Exit zero, generated Markdown, tool count,
link checks, schema checks and client assertion are insufficient. Measurements identify actual scope/
sampling and do not claim performance improvement or production bounds.

Report ordinary/native, external agent, review, human content approval, actual central publication,
image/CI, TLS/private Git, UID/mount and capacity gates separately. No target business execution,
Elasticsearch, production publication or runtime graph is implied. Operational recovery remains in
[Source MCP operations](source-mcp.md) and [Document MCP operations](document-mcp.md).
