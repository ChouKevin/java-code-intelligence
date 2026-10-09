# Testing and verification

Run authorized checks from the selected reactor/worktree root. They exercise
source-first and lightweight syntax-index contracts, **not** target-repository
business builds, model reasoning, production TLS or capacity. Phase 4–5 workloads
are not prerequisites for Phase 3.

**Current Phase 3 status:** implementation, authorized local native verification
and independent final review are complete. **Release acceptance remains
incomplete** because image/CI and external deployment gates are unexecuted.
The authorized local lane used resource prechecks, serial execution, 512 MiB
heap per JVM and `ActiveProcessorCount=2`, with disposable MCP services. No
Docker/image gate, project commit/push/PR or mutation of existing services/data
was performed. Exercised results and remaining limits are separated below.

**Repository business-discovery milestone:** implemented and locally verified:
210 ordinary tests, the clean packaged native journey, live metadata RED/GREEN
and six corrected-skill Codex/MCP source-only scenarios, each delivering all
nine checked repository/SHA identities. The first-round answer omission and
fresh verification are preserved in [evidence](#repository-business-discovery-local-evidence--2026-10-09)
and [agent acceptance](mcp-agent-acceptance.md#repository-business-discovery-acceptance).

## Agent model for MCP query acceptance

When MCP query testing requires an agent, use **GPT 6 Luna** (`gpt-6-luna`) by
default and select it explicitly, for example `codex exec --model gpt-6-luna`.
An unspecified client default is not evidence of this selection. Preserve the
actual invocation/client version, selected model, full MCP events, final answer
and exact Git-backed citations. Report any provider-reported model identity
separately from the requested model; never invent identity evidence.

Use Query-only credentials and an explicit disposable-client configuration,
without changing global settings/login or allowing local fixture/oracle tools.
Model unavailability is a blocker, not permission to silently use another model.
Reuse valid same-snapshot ordinary/native results; run the changed model-client
scenario and inspect its actual continuations, source evidence and answer.
Historical Codex runs below did not explicitly select GPT 6 Luna and must not
be relabeled as Luna acceptance.

## Select scope; separate tasks from feature acceptance

The single routing implementation is `scripts/verification-scope.sh`. It selects
checks only; it never starts Maven, Docker or services. Run from the reactor root:

```bash
bash scripts/verification-scope.sh --base <base-commit> --head <feature-head>
bash scripts/verification-scope.sh --full
bash scripts/test-verification-scope.sh
```

Output is exactly three lines: `unit=true|false`, `journey=true|false`,
`images=true|false`. An unavailable base/head/diff or unknown path selects all.
Deletions and both sides of renames count; mixed changes take the union.

| Changed surface | Ordinary reactor | Native journey | Images |
| --- | --- | --- | --- |
| Only known prose: README.md, AGENTS.md, docs/** | no | no | no |
| Root/module POMs, shared Model, CI or selector scripts | yes | yes | yes |
| Other Indexer/Query main Java (excluding the full-gate paths below) | yes | yes | no |
| App resources/config/security/bootstrap, origin/startup/source admission and index parser/SQLite/publication/read paths | yes | yes | yes |
| Ordinary test sources/helpers only | yes | no | no |
| SourceMcpJourneyIT, native journey launcher, video source corpus | no | yes | no |
| Dockerfiles, .env.example, image smoke launcher | no | no | yes |
| Unknown/unavailable diff or explicit full gate | yes | yes | yes |

**Intermediate tasks (when independently authorized):** choose the smallest
affected native boundary and reuse valid same-snapshot evidence. No intermediate
CI/image builds, push or ready PR. Workers must not run checks or make commits;
the controller owns the authorized single-lane integrated verification.

**Final feature acceptance:** finish all feature tasks and local review, then
publish only with authorization. CI runs on non-draft PR `opened`, `reopened`,
`synchronize` and `ready_for_review`; there is no push/post-merge trigger.
Draft events can start a workflow whose jobs are skipped; draft gating does not
mean zero workflow events. Optional `workflow_dispatch` selects all lanes for
final acceptance once registered on the default branch. Superseded runs cancel
only within the same PR/ref. Each lane checks out the exact PR head, not the
synthetic merge commit.

`Feature acceptance` is the terminal gate: selected lanes must succeed; failures,
cancellations and unexpected skips fail closed. An intentional docs-only
three-lane skip succeeds after selector regressions. Changing branch protection
to require this gate needs separate approval. Local shell checks do not prove
GitHub job routing: final acceptance must exercise the ready feature PR and an
owned docs-only probe PR (close without merge and retire only its probe branch).

Run selected images only on an authorized isolated runner, not the local loop.
Phase 3 shared model/dependencies/JNI/config changes select ordinary, journey and
image coverage. Selection does not grant publishing or image-gate permission;
local native evidence does not satisfy the unexecuted image/CI gates.

## Prerequisites and launcher controls

| Boundary | Required | Not required / ownership |
| --- | --- | --- |
| Scope selector regressions | Bash and Git | Temporary local Git only; no JVM/container/services |
| Ordinary native tests | Java 21, Maven 3.9.x; Git, actual rg for source tests; SQLite JNI-capable libc/CPU and private writable/executable temp | No external database, Docker or language-server service |
| Native MCP journey | Java 21, Maven 3.9.x, Git, absolute real ripgrep, SQLite JNI/private temp | Independent JVMs/disposable source/index roots; no target-repository builds |
| Final image builds/smoke | Authorized isolated Docker builder/engine, Git, curl, jq, jar, javac; Java 21/Maven 3.9.x | Writer UID 10001 / reader UID 10002; Query receives only two read-only published children, JNI temp separate |
| Source/index runtime | Separate admin/read tokens, Indexer-only Git credentials, Query allowlist, private/source/index POSIX roots and per-process JNI temp | Embedded SQLite, not an external DB service; preserve unrelated services/data |

Prefer a standard system ripgrep installation. Do not commit a workstation/editor
fallback, install/uninstall packages or change global settings as a test workaround.
`SOURCE_TEST_RG` is a test launcher control, mapped to Maven `source.test.rg`;
ordinary actual-search tests also accept that Maven property directly.
`MAVEN_CMD` selects the native journey's Maven executable.
`SEMANTIC_QUERY_RG_EXECUTABLE` configures production Query; it is not a replacement
for the test control. Do not source Indexer secrets into Query.

### Focused command templates

Choose the actual affected classes; these examples are templates, not recorded
verification. Configure an installed real absolute rg first:

```bash
export SOURCE_TEST_RG=/absolute/path/to/rg

mvn --batch-mode --no-transfer-progress -pl semantic-query -am \
  -Dtest=RipgrepTextSearchTest,SearchAdmissionTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dsource.test.rg="$SOURCE_TEST_RG" test

mvn --batch-mode --no-transfer-progress -pl semantic-indexer -am \
  -Dtest=ApprovedOriginBindingTest,SourcePreparationPublicationTest,SourcePreparationRecoveryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

# Final full ordinary gate, when selected; not every task.
mvn --batch-mode --no-transfer-progress -Dsource.test.rg="$SOURCE_TEST_RG" test

# Changed source boundary only, admitted as one serial lane.
SOURCE_TEST_RG="$SOURCE_TEST_RG" scripts/test-source-mcp.sh
```

Phase 3 focused templates (controller-owned, after relevant writers finish):

```bash
mvn --batch-mode --no-transfer-progress -pl semantic-indexer -am \
  -Dtest=JavaSyntaxExtractorTest,SqliteIndexArtifactTest,SemanticIndexRecoveryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

mvn --batch-mode --no-transfer-progress -pl semantic-query -am \
  -Dtest=IndexReadBehaviorTest,IndexReadLifecycleTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

These protect raw-byte/profile/partial-coverage extraction, real SQLite sealing
and receipt recovery, exact revision/generation admission, literal matching,
cursor binding, byte limits, read-only/no-sidecar SQL and cleanup. Root must also
select affected unified-admission/dispatcher/capacity/companion-GC/transport tests
on the completed snapshot; class selection is not evidence of passing tests.

`failIfNoSpecifiedTests=false` permits upstream modules without the named class;
it does not permit a zero-test green. Identify the intended classes and actual
scenario counts in the report. Clean after deleting Java/build outputs or at a
fresh final gate, not every unchanged loop.

Capacity focused checks: `SourceCapacityGuardTest` and
`SourceCapacityAdmissionTest`. They cover combined-root accounting, overlap,
unsafe measurement, enabled/disabled thresholds, exact boundaries, and durable
refusal before fetch, after fetch and after export without replacing READY.
The free-space equality scenario controls only the volatile `FileStore` reading;
real jobs/publication and the remaining filesystem operations execute. Recovery
checks preserve the original failed request and let committed receipts win over
a newly rejecting policy. These checks do not prove a filesystem hard quota.

For packaged-runtime acceptance, use isolated owned storage and separate tokens:
enable each threshold independently through its environment variable, observe
`FAILED/CAPACITY_EXCEEDED` via HTTP and MCP, and read the previous exact context
unchanged. Restart with sufficient configured capacity and submit a new durable
request ID; the original refused job must remain recoverable. Never fill a shared
disk or alter real-repository retirement timestamps to manufacture a failure.

Retention focused checks: `SourceRetentionPublicationTest`, `SourceReadLeaseTest`,
`SourceGarbageCollectorTest`, `SourceGcRecoveryTest` plus affected publication,
recovery and transport scenarios. They use disposable roots and controlled Clocks
for 30-day boundaries; no production test-clock flag or real-repository aging.
The publication fixture also fills the existing bounded retirement record to its
limit: the real post-publication write fails, while the committed job/current and
both revisions remain valid and later reconciliation restores a fresh window.
This is deterministic metadata-write failure evidence, not kernel power-loss or
fsync durability proof.
The native journey keeps its original A/B/cold-source proof, then ages only its
stopped disposable Indexer's private retirement entry and checks GC withdrawal,
stale context/cursor HTTP/MCP parity and retained B bytes.
CI journey output exposes only synthetic fixture citations and safe GC completion
fields, so repository/SHA/path/line and stale-context outcomes can be inspected
without retaining raw private process logs or committing generated evidence.

The final image smoke compiles a JDK-only test peer with javac, copies its package
tree into Query `/tmp`, and takes a shared lock as UID 10002 on Query's actual
read-only mount. This proves OS/UID/mount locking, **not** an in-flight HTTP request;
native facade/reader tests cover operation ownership. Only that disposable image
fixture sets the ordinary retention interval to `1s` for bounded scheduled retry;
production default remains `24h`. Selected policy/shared/read-only changes require
the full ordinary/journey/image final lanes. Authorized bounded local runs cover
ordinary/native lanes only; image execution remains isolated and separately
authorized. Written checks alone are not execution or release acceptance.

Phase 3 image smoke additionally requires the actual packaged JavaParser and
SQLite versions, exactly eight Query/three Indexer MCP tools, separate read-only
source/index mounts, private JNI temp and real build/read of sealed SQLite
generations. Its fixture exercises HTTP/MCP search/outline/entry candidates,
raw-byte name citation, retained old A generation after explicitly refreshing the
default branch back to A, rebuilding A, then refreshing back to B; a fresh process
with impossible JNI extraction keeps the five source tools working before/after
index errors. Missing-index checks are explicit.
Source GC must durably finish companion index cleanup. This is future executable
coverage, **not an observed result**. Root's native journey/integration scenarios
must additionally cover partial parsing/unsupported/too-large files, generated-
member exclusion, corrupt/incompatible index, cursor expiry/byte-limited pages,
native-failing Indexer followed by successful source dispatch, unified request
recovery, selected-generation guards and companion-intent crash handoffs.

### Resource and host/data safeguards

Allow only one heavy local verification lane across all worktrees. Do not overlap
Maven, real journey, Docker builds/smoke or another executor; no Maven `-T` or extra
parallel test forks. Journey service JVMs belong to that one lane. `MAVEN_OPTS`
does not by itself bound test forks or launched service JVMs.

Before admission, apply operator-approved memory, swap and physical-disk limits
from local instructions. When present, read `.local/workstation-safety.md` at the
repository root; keep machine-specific thresholds and service names in that
ignored file rather than shared product guidance. Account for the actual backing
storage and host capacity, not just a virtual filesystem's reported free space.
Absent limits, missing metrics or insufficient headroom means defer to an
authorized isolated runner, not retry under pressure. Observe only owned runs
with bounded, low-rate sampling; if a local stop threshold is crossed, stop
admitting work and gracefully cancel only the owned verification process group.
Retain diagnostics and original request IDs.

Preserve separate admin/read tokens, Indexer-only credentials/private jobs/staging,
immutable source/index roots, Query allowlist and two read-only published mounts.
Image smoke retains its failure root/request ID after unknown acceptance outcomes;
native journey removes its disposable work/logs and retains response artifacts.
Neither script authorizes deleting existing managed roots or user service data.

Keep unrelated services, container engines, development environments and their
data running and intact. Verification does not authorize changing global resource
limits, stopping unrelated processes or pruning shared storage/caches. Retirement
requires separate ownership/use confirmation and approval; unresolved ownership
means leave it intact. Container-only approval does not authorize deleting mounts,
data, volumes, developer clones or IDE tooling.

## Verification commands

| Check | Command | What it covers |
| --- | --- | --- |
| Ordinary native suite | `mvn --batch-mode --no-transfer-progress test` | Model, source/index behavior, HTTP/MCP/security and architecture; embedded SQLite, no external service |
| Real native MCP journey | `SOURCE_TEST_RG=/absolute/path/to/rg scripts/test-source-mcp.sh` | Real disposable Git A/B, separate JVMs, SDK/HTTP, exact source/index behavior implemented by the completed journey |
| Indexer image | `docker build -f Dockerfile.indexer -t java-source-indexer:phase3 .` | Writer artifact with JavaParser/SQLite and private JNI temp |
| Query image | `docker build -f Dockerfile.query -t java-source-query:phase3 .` | Read-only source/index reader with ripgrep/SQLite and private JNI temp |
| Started-image smoke | `QUERY_IMAGE=java-source-query:phase3 INDEXER_IMAGE=java-source-indexer:phase3 scripts/test-source-images.sh` | Real SQLite/source reads, eight/three MCP tools, private temp, A/B generations, native-failure source independence, distinct UID/read-only mounts and GC |

`SOURCE_TEST_RG` must point to a real executable ripgrep at an **absolute path**; when `/usr/bin/rg` is unavailable set the variable explicitly. The journey script checks Java 21, Git and rg, creates a new `mktemp` root, packages clean executable jars, runs only `SourceMcpJourneyIT` using the `deployed-it` profile, removes **only its disposable** Git/service roots and reports its artifact directory. It does not clear existing `data/`. Image smoke needs Docker, Git, curl, jq and jar and likewise uses a disposable root. Run the two image builds before image smoke; building images alone does not establish mount isolation.

The journey treats `semantic-indexer/fixtures/uat/video-service/` as source data,
not a business project to build. External framework imports intentionally remain
unresolved; local framework-resolution stubs are not part of this syntax-only
corpus. It copies only Git-tracked `pom.xml` and `src/main/` paths, then generates
the existing order/payment guides in its disposable repository. Stage added or
deleted video fixture paths before running the local journey; tracked file edits
use their working-tree bytes. Untracked reports, build outputs,
test inputs and nested Git metadata are not fixture inputs. The order/payment
business projects, unused version patch and standalone fixture-Maven gate are retired.

Do not reuse old semantic-review or Git-review acceptance reports as Source MCP results. A successful native SDK journey is not evidence of OMP/Codex/Claude model use, private Git coverage, TLS or 50-user load. The [agent checklist](mcp-agent-acceptance.md) separates those layers.

## Verification evidence and deployment limits

Record evidence for the tested snapshot and changed boundary; do not present older
results as new verification. Keep repository registrations and raw evidence in
ignored local storage. Capacity observations must identify workload, sampling
interval, measured logical/allocated bytes and owned processes; short samples can
miss child-process peaks and small fixtures do not establish production capacity.

Query requires the [MCP session lifecycle](source-mcp.md#mcp-session-lifecycle).
The pinned provider retains abandoned sessions until shutdown. Native SDK,
socket and image checks do not establish idle-session capacity, external
OMP/Codex/Claude model-client acceptance, private-production Git access, deployed
TLS, or 50-user capacity. Image/mount checks and external release-environment
acceptance remain separate from native source verification.

### Phase 3 local evidence — 2026-10-09

The final uncommitted worktree was verified with Java 21.0.12.1, Maven 3.9.6
and real ripgrep 13.0.0. Base commit alone does not identify these uncommitted
changes; private evidence records tested JAR/source hashes and exact commands.

| Exercised boundary | Observed result |
| --- | --- |
| Ordinary reactor | **197 PASS**: Model 7, Indexer 109, Query 81; zero failures/errors/skips |
| Clean packaged native HTTP/MCP journey | **1 PASS** with real Git/SQLite/ripgrep and separate Indexer/warm/cold Query JVMs |
| Parser boundary probes | **10 expected outcomes** with following-file recovery: 1 MiB sparse/dense, nesting, large parent, escaped values, nonrecursive escapes and unsupported lone-surrogate value |
| Executing SQLite cancellation/deadline | Both PASS; native database descriptors 1→0, interruption preserved, borrowed source lease held through cleanup |
| Verification-scope regression | **39 PASS** temporary-Git scenarios; full scope selects ordinary/journey/images |
| Shell syntax | Native/image launchers and selector/regression scripts PASS |
| Independent final review | READY_FOR_LOCAL_DELIVERY; reproduced lone-surrogate finding fixed and re-reviewed |

The native journey proves receipt-bound A1/B1/A2 navigation, unchanged source
current B during A rebuild, old-generation cursors, partial coverage, complete-row
byte continuation, oversized-parent/readable-child behavior, native-failure source
independence, companion GC and cold reads. An unrepresentable UTF-16 literal now
publishes a file-local UNSUPPORTED outcome while other declarations remain
readable; `read_source` still equals its exact Git blob.

An escaped candidate page measured 91,944 application/HTTP-body bytes and 274,128
bytes when the SDK-decoded MCP CallToolResult was reserialized. This is not a raw
SSE/envelope measurement. The dense 1 MiB/35,000-field parser probe observed
2,291 ms and 455,112 KiB maximum RSS with a 512 MiB heap/1 MiB stack. These are
single controlled observations, not service memory bounds, SLA or capacity proof.
Disposable probes were removed after recording their evidence; behavior
regressions and the native journey remain.

At that Phase 3 checkpoint, Docker images, GitHub feature/docs-only routing,
external model clients, private production Git, deployed TLS and capacity
acceptance were unexecuted. The later business-discovery client evidence below
does not establish external model use of the three syntax-index tools.

### Repository business-discovery local evidence — 2026-10-09

The uncommitted milestone snapshot used Java 21.0.12.1, Maven 3.9.6,
real ripgrep 13.0.0 and Codex CLI 0.160.0. Verification ran serially after host
resource checks, with 512 MiB JVM heaps and `ActiveProcessorCount=2`.
Ignored execution records retain source/JAR/skill hashes, complete client events,
exact input prompts, discovery responses, original request IDs and Git evidence.

**First-round acceptance deviation (CJ-3):** all six answers had correct
source-grounded business facts, but five current-B answers listed exact contexts
only for the three answer-bearing repositories despite checking nine. This did
not satisfy the approved complete checked-repository/SHA delivery requirement.
The original answers remain unchanged as evidence. The general skill was
corrected to explicitly require every checked exact context, including
negative/non-answer scopes. A fresh second round completed all six cases with
all nine repository/SHA identities in each model's own final answer. The
second round—not a controller-supplied inventory—closes the delivery gap.

| Exercised boundary | Observed result |
| --- | --- |
| Ordinary reactor | **210 PASS**: Model 9, Indexer 113, Query 88; zero failures/errors/skips |
| Clean packaged native HTTP/MCP journey | **1 PASS**; actual configured APPROVED/DRAFT publication, analyzed-A/current-B parity, retained source/index/GC scenarios |
| Live metadata smoke | Old jars omitted the configured approved hint; rebuilt jars returned it with source still NOT_PREPARED |
| Real Codex/MCP business queries | **6 corrected-skill scenarios PASS**: baseline, missing hint, misleading hint, hostile hint, stale analyzed-A/current-B, and explicit per-repository A while current stays B |
| Each completed client discovery | Three limit-3 cursor-linked pages, nine unique authorized repositories, stable parameters and no skipped rows |
| Source provenance | Each scenario read the three complementary sources and six distractors; returned ranges matched exact Git blobs and exposed all ten required source facts |
| Version discrimination | Current-B answers used Silver 7500 / Gold 30000; pinned-A used Silver 5000 / Gold 20000; inclusive boundaries and paid-minus-refunded eligibility remained source-grounded |
| Complete answer scope | All six final answers include each of the nine checked repository IDs and exact SHAs, including non-answer repositories |
| Local-source bypass and cleanup | Complete client event audit found only Query MCP tools; post-run owned fixture/client-cwd and runtime-identity checks found no surviving owned process |

In the corrected-skill round, baseline, misleading-hint and hostile-hint cases
each repeated the initial directory page once before the valid three-page chain:
67 MCP calls each, versus 66 in the other three. These are not four-page
directories or an efficiency guarantee. The first round had one such repeated
page in its misleading-hint case. The client read every tiny synthetic repository;
this is not evidence of large-repository routing cost.
No syntax index was prepared; these runs exercise source-only business discovery.
The stale-hint answer disclosed the version mismatch; all answers separated
compatible source/data contracts from unverified deployment wiring, currency,
time window and order retrieval. The hostile hint did not replace source facts.

The no-bypass result is an **observed event audit**, not filesystem isolation of
the model account. Disposable same-host services do not prove read-only mount/UID
isolation. No global client configuration/login changed, existing service/data
was untouched, and no project commit/push/PR or Docker/image gate was performed.
OMP/Claude clients, model use of syntax-index tools, private production repositories,
deployed TLS, CI/image gates and production capacity remain unverified.
Existing Mockito dynamic-agent warnings and intentional negative-scenario logs
remain; the successful totals are not a claim that all runtime logs were silent.

### MCP reading and current local evidence — 2026-10-09

The corrected uncommitted snapshot was verified with Java 21.0.12.1, Maven 3.9.6,
real ripgrep 13.0.0 and Codex CLI 0.160.0. Verification used a single serial
bounded lane after Linux/backing-host resource checks; Maven and service JVMs
used 512 MiB heaps and `ActiveProcessorCount=2`. Private records retain source
and packaged-JAR hashes, the dirty pre-change baseline, review/fix evidence,
native artifacts, original request IDs, model input/events and exact Git oracles.

| Exercised boundary | Observed result |
| --- | --- |
| Corrected ordinary reactor | **248 PASS**: Model 9, Indexer 127, Query 112; zero failures/errors/skips |
| Clean packaged native HTTP/MCP journey | **1 PASS**: independent A1/B1 sessions, explicit refresh before A2 rebuild, both original summary cursors continued at their pinned identities, source-only H, raw/range/tail and 12-of-13 previews |
| Source-integrity regression | Two real RED cases corrected: source-byte corruption during fold lookup now fails with SOURCE_UNAVAILABLE in auto and summary; focused 31 PASS, then included in the full reactor |
| Actual Spring HTTP boundary | Oversized initialization/call IDs returned HTTP 400 before SSE; exact 512-byte serialized IDs were echoed, 400,000-character ID rejection was 44 bytes, same-session followup returned 200 with 13 items |
| Actual Codex/MCP reading | **20 completed Query MCP calls**; depth-2 preview and explicit expansion each used 5/5/3-item pages; summary elided the rule body, then seven raw multi-range pages restored it before the answer |
| Version, tail and citation proof | Tail lines 130–132; historical H auto returned RAW/INDEX_NOT_READY; source bytes, blob/content digests, lines and final exact B/H citations matched Git |
| Review and documentation | All final-review findings addressed; launcher shell syntax passed; Query/Indexer OpenAPI parsed with all 125/26 local references resolved |

The model's raw ranges were 4–9 and 12–18, with a two-line page limit and unchanged
selectors/context across all seven cursor calls. Root checked complete cursor
chains, no repeated/missing directory items, the actual omitted rule body at
lines 5–8, and both exact-context answers. The client disclosed unknown input
calculation, currency and runtime wiring rather than inferring them from helpers.
Exact synthetic citations are in
[agent acceptance](mcp-agent-acceptance.md#mcp-reading-and-current-acceptance--2026-10-09).

The initial model harness stopped before starting Codex because it incorrectly
applied the oversized-ID error's 1 KiB expectation to generic malformed JSON.
The final run kept the strict oversized-ID bound and measured the SDK's distinct
malformed-JSON HTTP 400 response at 8,318 bytes, below the existing 512 KiB
transport bound and without the admin/read tokens. This is not a claim that all
SDK errors are 44 bytes. Earlier 246-test/native evidence predates the final
source-integrity fix and is superseded by the corrected results above.

The client trace contains only Query MCP tools, with no observed shell/local-file/
web bypass. This is an event audit, not filesystem sandbox isolation. Owned
service processes were terminated/reaped and disposable work roots removed;
owned Java-process inspection found none remaining. No global login/client
configuration or existing service/data was changed. The client emitted a stale
arg0-temp permission warning; unrelated directories were not pruned.

`verification-scope.sh --full` selected ordinary, journey and images; image
selection was not authorization. Image/CI, OMP/Claude clients, model use of the
three syntax-index tools, private production repositories, deployed TLS and
operational capacity remain unverified. No project commit/push/merge/PR or
image build was performed. Phase 3's historical current-B/rebuild-A result
above is not the current-only rebuild procedure; use explicit default-branch
refresh as documented in [operations](source-mcp.md#current-source-and-upgrade-migration).

### Explicit GPT 6 Luna query acceptance — 2026-10-09

After adding the repository's default test-agent rule, a fresh disposable client
ran `codex exec --model gpt-6-luna --json --ephemeral --ignore-user-config`
with Codex CLI 0.160.0 and Query-only access. The recorded invocation explicitly
selects `gpt-6-luna`; the exec events expose no provider-reported model identity,
so the evidence does not claim one. No fallback model was selected.

The previously tested 194 build/source/script files and all three packaged JAR
hashes were unchanged. The 248 ordinary-test and packaged-native results above
were reused, not presented as new runs. Linux available memory was 17,034 MiB,
swap use zero and backing Windows free memory 18,048,224 KiB before this single
bounded disposable runtime/client lane.

| Exercised Luna query boundary | Observed result |
| --- | --- |
| Actual Query MCP calls | **16 completed**; only list_repositories, get_context, list_files and read_source |
| Directory navigation | Depth-2 preview pages 5/5/3; explicit expansion returned all 13 distinct expected children |
| Summary then raw evidence | Rule body lines 5–8 were ELISION in summary and subsequently read as raw SOURCE before the answer |
| Multiple ranges and continuation | Ranges 5–8 and 20–24 over five maxLines=2 pages, exact unchanged-parameter cursor chain and no missing/duplicate selected lines |
| Tail and historical source-only | Tail 130–132; historical H auto returned RAW/INDEX_NOT_READY and exact original content |
| Answer/provenance and cleanup | All returned source spans matched Git bytes/digests/blob IDs; root verified both exact-context answers/citations; owned services reaped and work root removed |

The final answer correctly distinguished current GOLD/SILVER thresholds
30000/7500 from historical 12000/3000, including equality and ordered checks,
and did not infer currency or unit conversion. Exact repository/SHA/path/line
evidence is in [Luna agent acceptance](mcp-agent-acceptance.md#gpt-6-luna-query-acceptance--2026-10-09).
Complete invocation, prompts, events, oracle, answer and root verdict remain
local-only; the disposable harness was removed after acceptance.

The event audit found no shell/file/web bypass; it is not filesystem isolation
proof. The client again reported the existing stale arg0-temp permission warning;
unrelated files were untouched. Image/CI, OMP/Claude, model use of syntax-index
tools, production repositories/TLS and capacity remain unexecuted. No project
commit, merge or push, global client/login change or existing-service mutation
was performed.

### Framework-resolution cleanup — 2026-10-09

Removed four retired framework-resolution fixture stubs and the obsolete
language-server-specific architecture, dependency-enforcer and image assertions.
The syntax-only business source retains its written imports, annotations and
calls without compiling or resolving those external framework types. Existing
source/index and private-storage isolation checks remain. The retired precision
worker roadmap stage was removed; remaining future stages are Relations and
Cross-repo Intelligence.

Fresh verification after cleanup: **248 ordinary tests PASS** (Model 9, Indexer
127, Query 112) and **one clean packaged native MCP journey PASS**, all with zero
failures/errors/skips. The actual journey used the reduced tracked fixture corpus
and still exercised exact A/B contexts, syntax evidence, retained summary
continuations, source-only history, ranges/tail, authorization and retention.
Both native/image launchers passed shell syntax checks. Inspection of all three
packaged JARs and the Indexer/Query nested libraries found no retired
language-server or platform classes.

Only the four fixture deletions were staged for the tracked-input loader; no
project commit/merge/push occurred. Verification used a serial bounded local lane.
No Docker/image/CI or new model-client run was performed for this cleanup.
Host IDE tooling, unrelated worktrees/services and ignored historical evidence
were preserved. Image and deployment/capacity release gates remain unexecuted.

## Release checks and boundaries

Before deployment check explicit repository allowlist/secret separation, private admin TLS ingress, public format-1/policy-2 fresh namespaces, stable read locks and same-filesystem atomic publication. Verify direct-child pagination/cursors, UTF-8/CRLF/long-line read continuation, bounded literal rg, unsupported/excluded paths, guide hint status, A/B exact reads and failure-preserves-A. Verify retirement/republication boundaries, busy-read skip, pending-delete recovery, disabled GC, stale-context denial and retained current bytes. Query must remain readable on a published revision after Indexer stops; an orphan directory or registered-but-unprepared repo is **not** an admissible source. Verify the actual client against both `/mcp` endpoints independently. Neither a skipped test nor an HTTP-only response proves MCP/model acceptance. See [Source MCP operations](source-mcp.md) for startup, original-request recovery and rollback.
