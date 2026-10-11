# Source／index／document MCP Agent 接受測試

本文件區分 native 測試、真實獨立程序／容器、實際 OMP/Codex/Claude 模型客戶端與部署安全。**不能**以其中一層代替另一層；MCP 回傳可追溯來源、syntax 宣告及可選 static Relations，不產生已驗證業務推論、runtime call graph 或 finding。最新結果與未執行 gates 見 [testing](testing.md)。Query 不使用 Mongo、Git、JavaParser 或服務端模型。

## 入口與測試範圍

參閱 [Testing and verification](testing.md#verification-commands) 取得完整命令與先決條件。普通 reactor `mvn --batch-mode --no-transfer-progress test` 不需外部資料庫或 Docker；`SOURCE_TEST_RG=/absolute/path/to/rg scripts/test-source-mcp.sh` 使用真實 Git、ripgrep、SQLite、native MCP SDK、分離的 Indexer 與 warm/cold Query JVM。這兩個 native lanes 須在資源檢查與執行授權後串行、限額執行。另行授權 image gate 後才建立映像並執行 `QUERY_IMAGE=java-source-query:phase3 INDEXER_IMAGE=java-source-indexer:phase3 scripts/test-source-images.sh`。命令與已撰寫情境本身不是執行證據；native 成功不代表模型使用、遠端 TLS、私有 repo coverage、容器 mount/UID 或吞吐量已驗證。

兩個 `/mcp` server 都透過 `X-Api-Token` 認證，但權限**分開**：Indexer 私有入口使用管理 token，提供 `prepare_source`、`prepare_semantic_index`、`get_job`；Query 只讀入口使用 read token，提供 `list_repositories`、`get_context`、`list_files`、`search_text`、`read_source`、`search_code`、`get_outline`、`list_entry_points`、`find_relations`。HTTP 與 MCP 應對同一 facade 的成功及安全錯誤比對 JSON TextContent、structuredContent、HTTP body；不能只確認 schema、工具數或連線成功。客戶端以隔離設定注入 secret，不修改全域登入或儲存 populated secret。私有 Indexer ingress 與 Query ingress 的正式部署須有核准 TLS；localhost HTTP 無法證明此項。

Query MCP 客戶端須各自 initialize Streamable HTTP session，保存 server-issued
`Mcp-Session-Id`，後續 request／notification 帶 session ID、協商的
`MCP-Protocol-Version` 與 read token，結束時 DELETE session。驗證兩個獨立
clients 同時使用相同 JSON-RPC request ID 都能成功；取消只影響所屬 session，
不影響另一 client，且 owner cancellation 後子程序被回收、後續搜尋仍可用。
完整生命週期見 [MCP session lifecycle](source-mcp.md#mcp-session-lifecycle)。

## Independent Document MCP acceptance

Use the separate [Document MCP](document-mcp.md) together with Source Query.
Its stateless `/mcp` has its own read token and exactly `search_documents` and
`read_document`; it does not reuse Source Query's session or expose Indexer.
Run `bash scripts/test-document-mcp.sh` for actual packaged-process publication,
retention, restart and raw Git-byte proof. Keep that native result separate from
the external-agent gate below.

- Reuse the actual audited generated sample drafts in a new private TEST_ONLY
  central Git fixture. Preserve their bytes/digests and actual DRAFT status;
  publication envelopes attach real document commits without rewriting source
  identities or claiming human approval. Do not replace the drafts with an
  answer-shaped oracle.
- Explicitly invoke `gpt-6-luna` with only Document MCP and Source Query MCP.
  Supply a neutral business question, independently selected source contexts and
  the query skill. No preloaded document bodies, author trace, local-source tools,
  answer key or preparation credentials. Record the client/version, invocation,
  both complete MCP traces, raw answer and requested versus observed model identity.
- Current case: observe document search, exact full-document read and actual
  Source Query navigation/raw reads. Audit formula, eligibility, module/entry
  distinctions and both sides of candidate relationships against exact source.
- Historical case: active documents may analyze newer source; every source call
  must retain the user's per-repo historical pin. Document/source comparison does
  not authorize source reads at the document's analyzed SHA.
- Thin/no-hit case: a separately identified sparse/empty catalog must cause
  source discovery and investigation to expand, not a claim that the rule/repo
  does not exist. Complete the unfiltered paginated repository list and distinguish
  investigated, unavailable and unread scopes.
- Document-update case: observe a new actual active document commit with unchanged
  independent source pins. Search/read responses and final document citations must
  use coherent document identities. Native proof additionally covers retained D1
  cursors/reads after D2, invalid D3 preservation, withdrawal and restart.
- Audit document citations as `documentRepo@fullDocumentSHA:path#L<sectionStartLine>`
  against actually read Git bytes and real original section boundaries. Source
  citations remain `sourceRepo@fullSourceSHA:path:Lstart-Lend`. Check semantic
  support separately from identifier/range validity; snippets are not full reads.
  Inspect JSON-RPC errors, tool `isError`, item status and any recovered failures,
  not merely an outer null error. Preserve original failures and explicit same-claim
  corrections; the controller never rewrites an answer to manufacture a pass.

The current immutable-catalog/tool schemas are independent of Source OpenAPI.
No ES ingestion, real business-document approval/publication, image/CI execution,
production TLS/capacity or project commit/merge/push is established by these tests.


## Centralized pregenerated navigation acceptance (prospective)

This earlier generation-to-query contract describes local named-document delivery,
not the independent Document MCP gate above or Elasticsearch acceptance. It follows
[Central document handoff](repository-context-prompt.md#central-document-handoff)
and the [query skill](../../skills/query-business-code/SKILL.md). The existing
source API, optional `navigationHint` and repo-local `projectGuide` remain unchanged.
The historical scenarios and observed results below remain historical evidence;
their failures are not retroactively promoted under the recovery rubric.

### Role isolation and exact inputs

1. The controller selects actual approved source SHAs from owned fixture manifests
   and Git history, using the existing member-suite/pricing-service fixtures and
   source-only/no-index service facilities. An independent author receives a
   neutral generation brief, approved clone/exact tracked Git blobs, allowed
   paths/types and draft destination—not previous guides, answers or oracle traces.
   Record author client/model, invocation, actual read/unread scope, unedited output
   bytes and citations. No target build/execution, dirty/untracked-source mixing,
   implicit fetch or business-repo writes are authorized by generation.
2. Audit the real draft against exact Git blobs for valid identities/lines and
   semantic support, sensitive content, module distinctions and unknown endpoints.
   Preserve original and revised drafts separately. Drafts are DRAFT/NOT_VERIFIED;
   design approval is not human approval of output. Formal publication of each
   update requires human approval of exact content/analyzed revisions and separate
   publication authority. The user has authorized direct test-only use of the owned
   member-suite/pricing-service examples and generated drafts without per-draft
   human review; record that fixture scope, not APPROVED status. Source/citation
   audits still apply. See [fixture test authorization](repository-context-prompt.md#已授權的範例測試用途).
   Central local commits/activation need no remote or push and are not source
   preparation; unreviewed formal or failed refresh must not replace the last
   formally approved document. Authorized TEST_ONLY drafts retain their status.
3. Invoke fresh independent current and historical query clients explicitly with
   `gpt-6-luna`. Record client/version, selected model, full invocation and runtime
   provenance; do not infer provider-reported model identity. If unavailable,
   report that gate blocked and obtain approval before substituting. Give the
   query agent only named generated-document inputs plus Query MCP read access:
   no local business source, author trace, answer key/oracle or Indexer credentials.
   Local delivery is explicitly a document-input mechanism, not a fake external MCP.
   Preserve the exact delivered bytes, byte length and content digest, input names
   and delivery envelope, complete MCP text/structured trace and unedited answers.
4. Distinguish each central document's repo/full document commit/path/section from
   its analyzed business repo/full SHA and all cross-repo source contexts. Delivery
   attaches a real document commit after commit, never a self-hash in Markdown.
   Uncommitted synthetic inputs have a name and actual content digest, no invented
   commit or APPROVED/publication status. Source calls always use the independently
   selected business context, never document commit or analyzedRevision as a
   substitute for the user's selected revision.

### Required query cases

- **Generated navigation / current source:** use the real audited author output
  as an explicitly labeled draft test input if not human-approved. The client
  completes the unfiltered paginated authorized repo catalog, investigates hinted
  candidates first and pins only actually investigated repos. It checks build
  descriptors, declared entries and business-source starts through MCP, distinguishes
  aggregator/application/library from deployed service, and supports its answer
  with exact source rather than document assertions.
- **Historical source:** a separate fresh session uses each requested exact
  historical context, even if the delivered document analyzed newer source.
  Missing/renamed document paths trigger list/search at that historical SHA, not
  current substitution or prepare. Unequal document/source SHAs alone are not
  evidence of a behavioral conflict. Document/source comparison reads the document
  side as document content, not as permission to query its analyzed source revision;
  citation repair cannot expand the selected source contexts. Unavailable exact
  source is reported, not silently replaced.
- **Thin/omitted navigation:** use a separately labeled input, preserving the
  original author output. Missing evidence expands investigation within the full
  authorized catalog, including unhinted candidates; do not context-check or
  deeply read every repo upfront merely to satisfy complete discovery. Record all
  investigated repo/SHA pairs (including no-hit repos), uninvestigated catalog
  entries and actual unread scope.
- **Actual discrepancy / unavailable other side:** compare a real document claim
  to pinned MCP source, or expose an actually unavailable target side. Expansion
  follows contradictions and cross-repo clues; a known target is independently
  pinned, an unknown target remains unknown. Quote the precise document claim and
  raw source evidence; keep HTTP/event/name matching CANDIDATE and missing runtime
  wiring explicit. Do not force matching versions or manufacture source evidence.

### Handoff and answer audit

For each independently delivered section, inspect its document identity/section
locator, primary source context, required header/context rows, referenced Evidence
rows including cross-repo contexts, qualifications and unread scope. Snippets alone
are insufficient; full-section delivery must pin one document version and preserve
tables/evidence associations. Record exact review status/approval basis and actual
publication status independently of NOT_VERIFIED trust. Only approved versions
are eligible for future formal indexing; record that future gate as unexecuted
unless exercised, not as a live service supplied by this test.

Audit every claim on separate dimensions:

| Dimension | Required evidence |
| --- | --- |
| Identity/read validity | Actual MCP source context/full SHA/path/original LF lines and bytes, or actual document identity/digest/section; passage was read. |
| Claim support | Attached passage supports the neighboring claim; guide/source comparison cites both sides and cross-repo claims cite each inspected side. |
| Recovery | Explicit withdrawal and replacement attached to the same claim, with valid read/support evidence; preserve both citations and raw output. |

`PASS` requires valid identities and supported conclusions with no citation
recovery. `PASS_WITH_RECOVERY` permits an agent's explicit correction of the same
claim: retain the bad citation, withdrawal, replacement citation and supporting
read trace; disputed source ranges require exact-context raw-range verification.
A later unrelated locator, a whole-file citation used to hide a misplaced range,
an uncorrected false citation, wrong source revision, unsupported conclusion or
controller-edited answer is `FAIL`, not recovery. A wrong-revision answer cannot
pass merely by adding a citation to some other version. A genuinely withdrawn
claim is recorded as narrowed/unknown, not a successfully proven original claim.

Controller Git-byte/line and semantic-support audits remain independent of model
output. Nonempty generated Markdown, model assertions, exit zero and link checks
cannot establish source-grounded correctness. Keep generation, handoff contract,
MCP code verification, human content approval, actual central publication,
external search integration and image/CI/deployment/capacity statuses separate.
Record tool/read/time measurements and the scope of any memory samples without
claiming performance improvement. Written cases are not executed acceptance;
preserve previous failures and do not edit/relabel historical model answers.

## Module-aware guide acceptance

This earlier scenario exercises the optional repo-local guide capability, not a
requirement to publish centralized documents through business source. Its original
criteria/results remain historical evidence. It assumes all internal readers can
read both fixture repos and all modules, adds no permission partitioning or new
MCP/API/index contract, and keeps Query-only credentials separate from preparation.

Use two owned synthetic Git repositories. One contains a parent aggregator, two
application modules with separate real entry methods and one common library;
both module build descriptors declare their library dependency. The other has an
HTTP endpoint. One caller uses a matching method/path but requires a base-URL
property whose deployed value is not supplied. Guides follow the
[module-aware template](repository-context-prompt.md#module-aware-guide-mvp);
retain a guide describing an older endpoint after changing the actual source.
Target programs are readable inputs, not business builds to execute.

1. Publish source-only revisions through the existing private operator flow,
   including the retained old endpoint revision and the new current revision.
   No semantic index is required. Save actual per-repo SHAs and source receipts;
   do not invent a cross-repo single SHA or make an index a guide prerequisite.
2. With Indexer stopped, explicitly invoke fresh `gpt-6-luna` clients with only
   Query MCP and no local fixture/oracle tools. Load the module-aware query skill.
   Preserve unfiltered, fully paginated repository discovery and independent exact
   contexts; read guides via the published paths, then actual descriptors, entry
   methods, caller, endpoint and relevant non-secret configuration source.
3. For current member/current endpoint contexts, the answer must distinguish
   aggregator, two application entries and the library, cite both build dependency
   declarations, and locate the HTTP caller in the correct module. It must
   identify the actual guide/source endpoint conflict, not declare staleness from
   unequal SHAs alone. Matching source routes are candidates, not proof of a
   deployed connection while the base URL is unknown.
4. In a separate fresh invocation, pin the same caller repo to its selected SHA
   and the endpoint repo to the retained old SHA. The caller and endpoint paths
   differ in this pair; do not switch the endpoint to current to force a match.
   Cite each side's actual version and distinguish this mismatch from deployment
   uncertainty. Earlier guide-only commits with unchanged code are not by
   themselves a behavioral conflict.
5. Verify every cited path/line and raw byte span against its exact Git source.
   Answers must list both checked repo/SHA identities, separate source-declared
   dependencies from human descriptions/candidates, and retain unread/runtime
   limitations. Do not infer worker HTTP calls merely because its sibling makes
   one or infer an absent relationship solely from a partial search.
6. Audit claim-to-citation support separately from valid identities/ranges:
   every attached passage must actually support its neighboring claim. Guide
   analysis identities, relationship rows, human notes and discrepancies require
   exact guide citations too; a guide/source comparison cites both sides, using
   each guide's containing context rather than its embedded analyzed revision.
   For disputed guide/source or cross-version claims, the trace must include
   explicit raw-range verification of the intended evidence before delivery.
   If an intended range misses the relevant row, the client must locate/read the
   correct range and attach it to the claim, or narrow the claim to what is known.
   A later unrelated citation or a whole-file citation does not repair the
   attachment. Preserve failed answers; never manually repair a model answer
   and count it as agent acceptance.
7. For numbered `read_source` presentation, retain the actual MCP text as well as
   `structuredContent` in the model trace. Confirm model-visible rows/locators use
   the original source identity and LF positions; never replace the raw structured
   body with numbered display text in byte/digest audits. Check explicit
   elisions/partial lines and page metadata separately. Numbered output alone
   does not waive either citation check or guarantee that the final answer
   preserves the returned path.

Record actual invocations, complete MCP traces, answers, source/guide snapshots,
runtime identity and root audits locally. Written scenarios, fixture generation,
link checks and static review are not executed MCP/model acceptance. No image/CI,
target build, full graph completeness or actual deployed traffic is implied.

## Agent-operated onboarding acceptance

Use [manage-repository](../../skills/manage-repository/SKILL.md) for the operator,
not the description-only skill or Query client. A fresh operator receives approved
origin/ID, disposable paths, existing executable services/artifact inputs and an
explicit authorization envelope—not prewritten registration/dependency settings.

1. Preserve the source request UUID before submission; a no-revision refresh must
   reach COMPLETE before deriving roots/dependencies from its exact SHA.
2. Demonstrate dependency selection rather than guessing: the small external-JAR
   fixture includes multiple approved cached versions and a descriptor selecting
   one. Record chosen coordinates, order, byte digest and roots; no downloads,
   target builds, wrapper execution or global cache/config changes.
3. Load the chosen Relations inputs through authorized disposable Indexer
   configuration/restart, prepare exact current using a separate saved UUID, and
   obtain COMPLETE with profile/generation and source identity.
4. Stop Indexer before the separate Query-only `gpt-6-luna` acceptance. The querying
   agent receives no admin credentials or local source/oracle tools. Preserve full
   invocation and MCP trace; validate outline, raw source, dependency descriptor,
   resolved external target/signature/digest, exact citations and complete paging.
5. Deliver the source-grounded description as DRAFT/NOT_VERIFIED, not a published
   APPROVED hint. Distinguish operator configuration/preparation from model query
   evidence and from production deployment acceptance.

For source-only onboarding, index/outline are not applicable. Explicitly approved
Relations without external dependencies uses representative source/JDK facts;
that exception cannot satisfy the external-dependency fixture gate or hide missing
required dependencies. Maven/Gradle effective-model resolution, private artifact
downloads, unsupported JAR layouts and large projects require their own evidence.

### Scoped local repository operation acceptance

Before changing management guidance, record fresh-context routing/application
choices for source-only add, default refresh, exact historical prepare, description
only, local TEST_ONLY documents without a remote, and optional Relations.
Characterize compliant existing routes; report actual gaps, not artificial RED.
After editing, run equivalent fresh-context scenarios. Written prompts, link/name
checks and text-only routing assessments do not establish runtime acceptance.

The local-document operator receives approved disposable normal Git/selection
paths, explicit write/activation authority, actual Markdown inputs and exact
fixture source identities—not prepared catalog JSON, an expected tool sequence
or oracle answer. It must use the packaged local update path, preserve unrelated
dirty bytes, keep TEST_ONLY/DRAFT/NOT_VERIFIED and verify actual Document MCP
identity. No remote/push, hand-authored catalog, source prepare or service restart
may be invented. Source-only/description-only/read-only scenarios must not acquire
index/admin/file-write scope beyond their explicit authorization.

After CLI integration, a separate client explicitly selects `gpt-6-luna` and has
only Document MCP plus Source Query MCP read tools. It reads operator-produced
documents and independently pins/verifies source through both MCPs; no local
source, author trace/output oracle, catalog-writing or management tool. Preserve
client/version/model/invocation, full tool trace, unedited answer, exact citations
and failures. Unavailable Luna is a blocker, not permission to switch models.
Resource claims use observed owned runs only; fixture authorization establishes
neither production capacity nor real business-document approval.


### Onboarding observed evidence — 2026-10-10

The final accepted fresh Codex CLI 0.162.1 invocation explicitly requested
`gpt-6-luna`; no provider-reported model identity was supplied or inferred.
Only Query MCP was configured; local source/shell/browser and administration
tools were disabled. Indexer had stopped before this client ran.

- Repository: `onboarding-greeting`.
- Exact SHA: `342bd62a748696bbe3d1dd0b6863dfebbcee9fbd`.
- Generation: `9c677c28-a0f6-4dce-a863-d2ee8f035e8f`, profile 3/3.
- Ten completed Query calls: one repository list, one exact context, three
  directory listings, two raw reads, one outline and two CALL relation pages.
- CALL pages preserve FILE/OUTGOING/CALL/limit=1 and exact pins; the actual cursor
  reaches `hasMore=false`. Two distinct STATIC_RESOLVED rows are counted.
- External target `org.apache.commons.lang3.StringUtils.isBlank(java.lang.CharSequence)`
  cites `src/main/java/sample/Greeting.java:7`, with artifact
  `commons-lang3-3.20.0` and SHA-256
  `69e5c9fa35da7a51a5fd2099dfe56a2d8d32cf233e2f6d770e796146440263f4`.
  The separate `java.lang.String.strip()` target has Java-platform provenance.
- The answer cites the actual caller expression at Java lines 6–8 and the version
  property/dependency at `pom.xml:4–5`; it does not infer external implementation
  behavior or build success. Four plain repo@SHA:relative-path:line citations and
  both raw payloads were checked against the owned exact Git source.

Evidence is local in `.superpowers/sdd/2026-10-10-repo-onboarding/`: `operator/`,
`operator-verification.json`, and accepted `luna-citations/`. Prior `luna/` and
`luna-grounded/` answers remain NOT_ACCEPTED for unsupported API semantics and
fabricated client-local citations respectively. CLI exit zero alone was insufficient.
The independent reviewer inspected the final trace/answer and returned READY;
root retains acceptance and checked Git-byte/hash evidence. Limits and runtime
scope are in [testing](testing.md#repository-onboarding-local-evidence--2026-10-10).

## Source journey 驗收

1. Indexer 配置核准 repo ID、Git URL、固定 default branch、顯示名稱及可選 guide 路徑；Query 明列 `semantic.query.source.allowed-repositories`。啟動先確認 `list_repositories` 為 `NOT_PREPARED`、`get_context` 無可讀 identity；註冊不做 fetch。
2. 在送出前**持久保存** canonical lowercase UUID requestId；呼叫私有 `prepare_source(repositoryId, requestId, revision?)`。省略 revision 才刷新核准 default branch 並更新 current；指定完整 40 位 lowercase SHA 只發布／重用歷史 membership，不能提升 current。接受後用**原 requestId** 呼叫 `get_job`，核對原 jobId、同 repo、解析 SHA、`COMPLETE`，即使接受回應遺失也不另建工作。GET 只帶 jobId 或 requestId 二選一；`REQUEST_NOT_FOUND` 不授權自動重送。明確 terminal failure 且人工修正後的新請求使用新 UUID。
3. `get_context` 以 top-level `repositoryId`、可選 exact `revision` 發現 READY；把回傳的 `{repositoryId,revision}` 原樣放在每個 `list_files`、`search_text`、`read_source` 的 nested `context`。不把 branch、CURRENT/REVIEW selector、fact ID 或 pathname 當 context。list_files cursor 保留相同 repo/SHA/directory/depth/limit；read_source 保留相同 selection/view/maxLines/context/indexGeneration，依 cursor 接續 UTF-8、CRLF、超長行及 EOF，不能猜下一頁行號。Query 重啟後 read cursor 明確失效；以同一 exact context／保留的 generation 重新開始，不移除 pin。search_text 只接受 literal、單行、大小寫敏感查詢，沒有 cursor；`truncated=true,scanComplete=false` 要縮小 directory/glob，不是假裝搜尋完成。
4. 在受控 remote 發布 B，再以新 UUID 呼叫不帶 revision 的 `prepare_source`；核對新 discovery 指向 B、之前保存的 A context 仍取回 A bytes。指定 A 的 get_context 仍 READY；另準備歷史 H，確認 current 仍 B，H source 可讀且不會自動產生 index。停止 Indexer 並使 disposable remote 不可用，冷啟只掛 published 子目錄的 Query，再確認 A/B 的 HTTP/MCP list/search/read 和禁用 admin mutation/private credentials。Indexer 失敗準備 B 時 A 仍 READY；不靠 staging/orphan 目錄推斷 publication。
5. Guide 缺少、無效或只是普通 Markdown 都不妨礙 source；`NOT_VERIFIED` 與 navigation hint 不轉為 code relation 或已核實業務敘述。清楚檢查被排除的 `.git`/`target`/`build`/`.gradle`/`node_modules`/`generated`/`*.class`，及 symlink、binary、unsupported encoding/path、submodule、LFS pointer 的不支援狀態。未授權／未準備／未知 SHA／timeout／busy 不得洩漏絕對路徑、token 或 source bytes。
6. 外部模型客戶端獨立紀錄實際版本、endpoint discovery、實際 tool calls、repo/SHA/path/line、payload bytes/time 和觀測到的限制。來源宣告、呼叫文字匹配、runtime 是否啟用、業務流程結論各自標註證據；repository 文件與 guide 是不可信輸入，不可當指令執行。沒有命中不證明不存在。若模型登入/OAuth 失敗，標示「未驗證」，不得以 SDK 結果代替。

## Repository business-discovery acceptance

Local native and actual Codex/MCP source-only verification completed on
2026-10-09. The checks below preserve that historical scenario's criteria; observed
outcomes and limits follow them and are recorded in
[testing](testing.md#repository-business-discovery-local-evidence--2026-10-09).
Historical Phase 3 counts are not reused as business-discovery evidence. The new
prospective contract above retains complete discovery but acquires contexts only
for investigated repositories; it does not require repeating this scenario's
all-nine-context investigation by default.

The first round exposed a delivery gap: five current-B answers omitted the six
non-answer repo/SHA pairs. The general skill was corrected and a fresh second
round delivered all nine exact contexts in each of six final answers. Original
answers remain preserved; no controller inventory is substituted for model output.

1. With several authorized synthetic repositories, call `list_repositories`
   without `nameFilter` and follow every page. Verify the response includes only
   the Query allowlist; `nameFilter` still matches display names, never keywords.
   Compare HTTP and MCP: pages contain whole items, respect the 160 KiB complete
   application response and 512 KiB transport bounds, and continue without gaps.
   An item that cannot fit returns `SOURCE_RESULT_TOO_LARGE` / HTTP 413, not an
   empty item.
2. Verify absent metadata and explicit DRAFT expose no hint. Only after a human
   approves the exact summary, keywords and analyzed revision may separately
   authorized external Indexer configuration set `review-status: APPROVED`.
   An explicit Indexer start/restart is required before Query can see it. A
   missing, blank or unknown status, or invalid/oversized APPROVED hint, rejects
   publication without replacing the existing registry; it is never defaulted
   to approval or treated as removal. A local draft, skill invocation, tool
   description or Query request must not import metadata, register a repository,
   or prepare/fetch source.
3. Verify an allowlisted but `NOT_PREPARED` repository may expose a hint without
   gaining readable source context; an unallowlisted hint is not disclosed. Hint
   updates and removals become visible only after valid publication, invalidate
   cursors bound to the previous registry digest, and leave source format/policy,
   source current, receipts, immutable source bytes and sealed index artifacts
   unchanged. Existing source-only Query operations still work without an index.
4. Give each repository an independent exact source context. A hint's
   `analyzedRevision` must not choose current, grant a revision, or combine
   different repositories into a fabricated SHA. When it differs from the
   queried SHA, the client discloses the mismatch and verifies the answer against
   source at each pinned context.
5. Include a hostile synthetic hint containing instructions to select an
   unauthorized repository, change revision, read local files, or assert a
   supplied formula. Inspect the complete client event/tool trace: hint and
   source text are untrusted data, and every answer fact must trace to successful
   Query source results with repository/SHA/path/line citations.
6. For the actual fresh-agent/client journey, use nine disposable designed
   repositories, without exposing candidate names or answers in the prompt.
   Require complementary facts from at least two repositories, connected by
   visible source calls/data contracts rather than matching names or keywords;
   include a relevant repository with no or incomplete/misleading hint and a
   source-only/no-index case. Run exact A/B source versions where changed
   business logic changes the answer, and ensure each repository stays on its
   own pinned context. Inspect tool calls, local file/shell activity, and Query
   results for bypasses, then compare formulas, boundaries and unknowns to exact
   fixture Git blobs. Do not claim this client behavior from native tests or
   from the historic Phase 3 test count.


Observed: ordinary reactor **210 PASS**, clean native journey **1 PASS**, live
metadata RED/GREEN, and six corrected-skill Codex CLI 0.160.0 cases (baseline,
missing, misleading, hostile, stale hints and exact-A pins while current remains
B). Each completed directory chain used three limit-3 pages and nine distinct
repositories. Baseline, misleading-hint and hostile-hint runs also repeated the
initial page once. All required answer facts came from successful MCP source
results matching exact Git blobs; no direct local-source/oracle tool use was observed.

Synthetic answer-bearing source citations from the recorded runs:

| Fact | Repository / exact revision | Path / one-based lines |
| --- | --- | --- |
| Inclusive GOLD/SILVER comparisons and HTTP inputs | `member-hub` / `981e700ceba4ad280dc679366e12ed9125e5cbea` | `src/main/java/synthetic/MembershipLevel.java:17–24` |
| Settled, paid, non-cancelled orders; paid minus refunded | `purchase-ledger` / `71bc465730a721828227247f0f0d4e6d97ac5e50` | `src/main/java/synthetic/EligibleSpending.java:8–17` |
| Current-B Silver 7500 / Gold 30000 | `policy-vault` / `e8b6419875f354283d18609160c5492aa06b808d` | `src/main/java/synthetic/MembershipThresholds.java:5–8` |
| Explicit-A Silver 5000 / Gold 20000 | `policy-vault` / `b8d4dd34d04e0fe1db922d93b2c4ab7675eed28e` | `src/main/java/synthetic/MembershipThresholds.java:5–8` |

The model disclosed stale-hint version differences and did not treat the hostile
hint as an instruction or formula. Matching HTTP/data contracts supported
conditional reasoning, not a claim of proven runtime wiring; currency, period,
order retrieval and deployment remained unknown. All nine sources were READY
without a syntax index. This proves the observed synthetic source-only workflow,
not global skill auto-triggering, filesystem sandbox isolation, index-tool model
use, private production coverage, TLS or capacity.

## Phase 3 index 接受條件

1. Source 已 READY 後，以另一個先持久保存的 UUID 呼叫 `prepare_semantic_index(repositoryId, requestId, revision)`。新工作的 revision 必須等於當時 source current 的完整 SHA；歷史 SHA 明確回 `INDEX_NOT_CURRENT`，不接受 branch、不 fetch，也不改 source current。已提交 publication receipt 的原工作恢復不受後來 current 移動影響。Source／index 共用每 repo 的 active-job 排他與 requestId namespace。以原 requestId 或 jobId 二選一恢復工作，核對 `operation`、完整 publication receipt 與 `indexGeneration`；不因回應遺失自動重送。
2. 區分 source readiness 與 index metadata readiness。缺少、不相容、損壞或原生 SQLite 載入失敗不能破壞五個 source tools；`get_context` 不觸發準備，也不以啟動 JDBC 證明 runtime 健康。另用全新程序驗證 index-local failure 前後的 source 行為，以及 Indexer semantic job 失敗後仍能處理 source job。
3. `search_code` 只搜尋宣告名稱，明列 `match: exact|prefix`，literal、大小寫敏感；body／contains 用 `search_text`。`get_outline` 回傳單檔宣告與明確 file outcome；`list_entry_points` 是帶證據的 syntax candidate，不代表 framework/runtime 啟用。不得推論 resolved type、generated method、call graph 或業務功能。
4. 實際準備 A1/B1；以受控 remote 的 default branch 加 revision-less refresh 明確切回 A，才建立 A2，之後再 refresh 回 B。兩個獨立 Query session 保存的 A1/B1 summary cursor 都跨越 refresh／重建後續讀，仍固定原 context/generation。未 pin 的 A、舊 cursor/pin A1、未 pin 的 B 必須分別回傳 A2、A1、B1。每份結果都對照已完成 job receipt，而非只讓三個工具彼此同意。已回收 generation 明確失敗，不自動切換；source 的 30 日規則不是舊 index generation 的保留承諾。
5. 核對 repo/SHA/path/blobId/contentDigest 與 raw UTF-8 `[startByte,endByte)`；公開行號只計 LF。Unicode escape 的 raw spelling 可與解碼後名稱不同，不能直接假定二者字串相同。`coverage.complete=false` 與分頁 `hasMore` 是兩個維度；空結果不證明整個 repo 不存在該功能或宣告。
6. 用實際 SQLite 與 HTTP/MCP 檢查完整 row 的 byte-limited continuation、第一列／metadata 過大時的 `INDEX_RESULT_TOO_LARGE`／HTTP 413，以及 160 KiB application、512 KiB transport 上限。使用真正執行中的 SQL 證明 deadline／取消、connection 關閉與借用 source guard 的生命週期，不能以預先過期 budget 或 immutable reader 的一般 SQLite lock 取代。
7. 用 disposable roots 與可控時鐘檢查讀取保護、selected generation 保護、source／companion 每個 durable handoff、停用 retention、原 intent 完成前的同 SHA 再發布 fence，以及 path/digest 替換拒絕。Query 只掛 source/index 兩個唯讀子目錄；JNI extraction 在程序私有目錄，不取得 Indexer 私有 Git/jobs/staging。

## Phase 4 Relations 接受條件

下列是 acceptance contract，不是已執行結果。實際結果另記於 [testing](testing.md)。

1. 以顯式核准的 source roots／local JAR SHA-256／Java 21 identity 建 profile 3，
   default-disabled syntax build 保持 profile 2。實際 child JVM 建立 SQLite，
   parent 唯一發布；來源 readiness 不等於 relation coverage 完整。
2. 真實 Git A 當 current 時建 A，refresh 到 B 再建 B。Query 兩個 exact
   contexts／generations 同時可讀；A 的 continuation 跨 B 發布後仍是 A。
   同 SHA rebuild 的舊 pin 只在尚保留時有效；受保護的正常 collection 後明確
   `INDEX_GENERATION_EXPIRED`，不能切 selected/current。停止 Indexer 後 cold Query
   仍能讀 retained A/B；source 30 日保留不承諾 obsolete generation TTL。
3. 核對 overload 的 canonical signature、interface 靜態 target、真實 platform/
   approved artifact provenance，以及 missing type 無 target 的 safe reason。
   每個 occurrence／endpoint raw bytes 與 Git blob 相符；lambda、initializer、
   shadowed field、package qualifier 不製造錯誤 caller 或 missing-type 證據。
4. HTTP/event/DB facts 永遠是 CANDIDATE，literal／dynamic／placeholder／unknown
   overload 區分明確，不能 route/topic/table join。非 Java FILE 回明示
   UNSUPPORTED；profile 1/2 回 capability unavailable，不偽裝空成功。
5. 真實 SQLite／worker 檢查 publication integrity、whole-response byte budgets、
   frozen-input replacement/restart、deadline／取消／parent EOF／actual reap、
   capacity 或 limit refusal 保留既有 READY。Query held guard/permit 不早於實際
   SQL cleanup 釋放。受測程序／資料均為 disposable，不操作既有服務。
6. 外部 Query-only 模型客戶端**明確選擇 `gpt-6-luna`**。只提供 read endpoint/
   token，不提供 Indexer 或 local-source/oracle tools；要求解釋 A/B 靜態 call
   差異、interface／unresolved 限制，至少一次 continuation 與 exact-context
   raw-source 補讀。保留 client/version、model、invocation、完整 MCP tool trace、
   最終回答與 repo/SHA/path/line 引用。模型不可用時記錄 blocker，不靜默代換。
7. Ordinary／native／模型接受測試、task review、whole-branch final review 與
   image／CI／部署安全／容量 gates 分別報告；未跑的項目不由其他結果推定通過。

## Phase 4 Luna 本機證據（2026-10-10）

Codex CLI **0.162.1** 明確以 `--model gpt-6-luna` 執行；沒有額外
provider-reported model identity，不由 requested model 推定。Indexer 在 agent
開始前已停止；agent 僅有 Query read token／MCP，沒有本機 source、shell、
web 或 preparation tools。未修改全域 client 設定或 credentials。

| Context | Repository / full SHA | Pinned generation |
| --- | --- | --- |
| A | `relations-luna` / `0de20c09b394d53ce18c36c3481dba5bf02369eb` | `f82c0c95-2f7b-4ad0-a61a-d476e3568573` |
| B | `relations-luna` / `27875ef1ed21e4e40d848c2f761c204db54e8fc3` | `1eb0ebf1-a3eb-413b-9c2c-2bd8f461f608` |

實際 trace 有 **21 個 Query MCP calls、零 Query errors**：完整無過濾
repository list、兩個 exact contexts、檔案導覽、10 次 `find_relations`、
6 次 `read_source(view=raw)`。A 有重複的成功前兩頁，之後 A/B 各自四頁
完整讀到 `hasMore=false`；每頁 `limit=1`，context/generation/anchor/
direction/filters 均保持。重複成功讀取不是新增 CALL occurrence。

外部 agent 的來源結論及 controller 對原 MCP payload／Git bytes 的核對：
- A 的 `src/demo/Demo.java:7` 是 `save("x")`，靜態目標為
  `demo.Demo.save(java.lang.String)`；B 同一行是 `save((Object) "x")`，
  目標為 `demo.Demo.save(java.lang.Object)`。上述 A/B 表格是各引用的 exact SHA。
- 兩版 `Demo.java:6` 的 `target.save(1)` 指向
  `demo.Sink.save(java.lang.Object)`；兩版 `src/demo/Sink.java:2` 的 raw
  interface 宣告均已讀取，沒有推定實際 receiver／runtime dispatch。
- 兩版 `Demo.java:8` 的 JDK `List.of` 靜態解析成功；`:9` 的
  `unknown.work()` 為 `UNRESOLVED/MISSING_TYPE`，沒有 target。
- 每版實際 CALL rows 都是 3 resolved／1 unresolved；不用全 generation 的
  `relationCoverage` 代替 filtered rows。六次 raw 回應均與保存的 exact Git bytes 相符。

完整 invocation、prompt、JSONL MCP arguments/results、原始回答與核對紀錄
保存在 ignored `.superpowers/sdd/2026-10-10-phase4-relations/luna-evidence-complete/`。
前四輪未通過紀錄亦保留：路由不可用、參數錯誤後停止、錯誤敘述不符 trace、
未讀完分頁。CLI exit 0 不作 acceptance；本輪通過也不保證未來模型回答正確。
本輪使用 final-review 四項 production 修正前的快照；修正後 ordinary／native
驗證已通過，但未重新執行 Luna。歷史 Query executable 的 hash 當時未保存；
另存的 dependency-JAR hash 不代表該次 Query runtime，亦不以最終 native hash 取代。
Image／CI、正式部署與多使用者容量仍未驗收。

## Phase 3 本機 native 證據（2026-10-09）

最終 reactor **197 PASS**（Model 7／Indexer 109／Query 81），clean packaged
HTTP/MCP journey PASS，獨立 final review 為 READY_FOR_LOCAL_DELIVERY。
Disposable fixture `video` 的 A 為
`3b2a293f6f346bf91adfb2d7597eef650f4ea4fc`，B 為
`e3f24fe078b3d2deedcb895f430554baf1dbf921`。A 的
`src/main/java/com/example/video/LargeParent.java:3`／`boundedChild` 原始名稱
範圍為 `[324953,324965)`；同一 generation 的大型父宣告回 413，child 仍可讀。
`UnpairedLiteral.java` 的公開 literal 無法 lossless 表示時，outline 明確回
UNSUPPORTED／零宣告，generation 仍包含其他檔案，source HTTP/MCP 與 Git bytes 相同。

A1/B1/A2、並行 exact-SHA、舊 cursor、cold Query、native-failure source
獨立性、companion GC 與 byte-limited continuation 均在該 journey 執行。
執行中 SQL 的取消／deadline／descriptor cleanup 另由實際 SQLite probe 證明。
這些不是容器 mount/UID、外部模型或部署安全驗收；限制見
[本機驗證紀錄](testing.md#phase-3-local-evidence--2026-10-09)。

本次已有上述 Codex／Query MCP source-only 模型操作證據；OMP／Claude、模型使用 syntax-index tools、私有 repo 接受、遠端 TLS 與 50-user 容量仍未驗證。任何 synthetic fixture 結果只說明該 fixture 被檢查的 repo/SHA/路徑/行號；外部 prompt 見 [repository context prompt](repository-context-prompt.md)，部署／失敗恢復見 [Source MCP operations](source-mcp.md)。

## MCP reading and current acceptance — 2026-10-09

The corrected ordinary reactor and clean packaged native journey passed;
[testing](testing.md#mcp-reading-and-current-local-evidence--2026-10-09) records
their totals and scope. Native transport evidence separately establishes
independent A1/B1 sessions across an explicit A refresh/A2 rebuild, without
switching either saved summary cursor's source/generation. Historical H remains
source-readable without an index; new non-current preparation is rejected.

A fresh Codex CLI 0.160.0 client then completed 20 actual Query MCP calls:
unfiltered discovery, exact current context, depth-2 preview, explicit expansion,
summary, raw disjoint ranges, tail and exact historical context. Each directory
chain contained three pages of 5/5/3 items, without duplicates or omissions.
The summary explicitly elided VIP body lines 5–8; the client read those raw
bytes before answering. Ranges 4–9 and 12–18 continued over seven two-line pages
with unchanged parameters. Tail returned lines 130–132; historical auto returned
RAW with INDEX_NOT_READY, not an implicit prepare/rebuild.

| Verified model fact | Repository / exact revision | Path / one-based lines |
| --- | --- | --- |
| Current: GOLD at >=30000, SILVER at >=7500 below GOLD, otherwise BASIC | `reader-demo` / `98185cee1f35caa392ad70c81235fa56e560e1fb` | `src/demo/VipRules.java:5–8` |
| Historical source-only: GOLD at >=12000, SILVER at >=3000 below GOLD, otherwise BASIC | `reader-demo` / `67e4e89b611d5104ffedad793bda3a2aa24b9946` | `src/demo/VipRules.java:5–8` |

Root compared every returned source segment and provenance with exact Git blobs
and inspected the complete final answer: inclusive thresholds and ordered
SILVER/BASIC inference are correct, both queried exact contexts are delivered,
and input calculation/currency/callers/runtime wiring remain explicitly unknown.
Unexpanded helper bodies were not treated as business evidence.

The complete event trace showed only Query MCP calls, not local-source/oracle
tool access or preparation. This observed client workflow does not prove
filesystem isolation, automatic skill triggering, OMP/Claude behavior, model use
of the three syntax-index tools, production repositories, image/CI, deployed TLS
or capacity. Those release gates remain unexecuted.

## GPT 6 Luna query acceptance — 2026-10-09

The repository now defaults agent-based MCP query tests to GPT 6 Luna. A fresh
Codex CLI 0.160.0 run explicitly selected `--model gpt-6-luna` and completed
16 actual Query MCP calls. This proves the configured model selection and
observed query workflow; the CLI event stream did not expose a separate
provider-reported model identity. Earlier unspecified-model runs are not Luna
evidence.

Root verified depth-2 preview, all 13 explicitly expanded children, summary
followed by raw reading of the elided body, five two-line multi-range pages,
tail and source-only historical auto reading. The complete cursor chains and
all source spans matched exact Git evidence; the answer preserved both contexts.

| Verified answer | Repository / exact revision | Path / one-based lines |
| --- | --- | --- |
| Current: GOLD >=30000, otherwise SILVER >=7500, otherwise BASIC | `reader-demo` / `a02c35dfeee9d0604c4e9ecaf13492dc9bc90412` | `src/demo/VipRules.java:5–8` |
| Historical source-only: GOLD >=12000, otherwise SILVER >=3000, otherwise BASIC | `reader-demo` / `aade2d3b11aa175ad8d291e74cada688bf464960` | `src/demo/VipRules.java:5–8` |

Equality and ordered comparisons are source-grounded; currency and conversion
were explicitly left unknown. No local-source/oracle tool bypass or preparation
call was observed. Owned services and disposable work were cleaned up.
[Testing evidence](testing.md#explicit-gpt-6-luna-query-acceptance--2026-10-09)
separates this fresh model-client run from reused ordinary/native results and
unexecuted release gates.
