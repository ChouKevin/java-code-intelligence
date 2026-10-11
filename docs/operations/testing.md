# Testing and verification

Run authorized checks from the selected reactor/worktree root. This guide selects
lanes for source-first reading, Java syntax, opt-in static Relations and independent
Document MCP. It does not authorize target-repository builds, service/data changes,
Docker, publication or Git integration. Native success is not external-agent proof.

## Current verification status

Current evidence is recorded below. The historical table is limited to its earlier code/runtime and does not establish the new per-owner contract.

Integration verification ran a fresh full-reactor `clean package`: 401 tests,
400 passed, one ACL-view platform skip, zero failures/errors. Module counts:
Model 9, Indexer 172, Source Query 139, Document Query 81.
Packaged Document CLI/MCP journeys passed all three scenarios with zero skips,
including real unprivileged publication-failure recovery. Source/Relations
packaged journeys and external-agent evidence were not rerun by this integration
gate; their separately recorded proof remains limited to its original scope.

The first integrated Document run had one incidental race-test error-code
assertion defect. That assertion was removed; subsequent operation-identity,
HEAD/branch, safe-removal and first-publication cutover repairs passed the final
ordinary run. A backing-host capacity pause deferred their pre-fix execution;
the later successful run is not retroactive RED evidence. Verification resumed
only after headroom recovered, without changing unrelated services or global limits.

Fresh Query-only dual-MCP acceptance used Codex CLI 0.162.1 with explicit
`--model gpt-6-luna`, isolated read credentials, complete unedited traces/answers,
and exact source/document locator and claim-support audits. Provider model
identity was not independently reported. Four scenarios used six invocations:

| Current scenario | Result | MCP calls | Demonstrated boundary |
| --- | --- | --- | --- |
| Neutral business navigation | PASS | 21 | Full document navigation, complete unfiltered repository discovery and raw source evidence |
| A update, stale global/A lookups, unchanged B | PASS_WITH_RECOVERY | 59 | Induced stale errors recovered; original B context/cursor remained valid; Document/Source busy errors were explicitly recovered |
| Neutral question after genuine source rollback | PASS_WITH_RECOVERY | 28 | Forward document commit, unchanged B, fixed source pins; an out-of-range package citation was explicitly withdrawn and replaced for the same claim |
| Last A document removed | PASS | 19 | Empty document search led to source evidence, not a claim that the feature disappeared |

The operator also removed the final B document: global/scoped document searches
succeeded empty while both source policies remained readable. Unrelated working
bytes and Git history were preserved. Author handoffs remained TEST_ONLY/DRAFT/
NOT_VERIFIED and required recorded provenance, wording and locator corrections;
author results are not substitutes for the separate Query runs. Original failures
and corrections remain private evidence, not retroactively clean passes.

Scoped and whole-branch static review findings were resolved. These local results
do not constitute deployment approval, model-wide reliability or production sizing.

| Historical evidence boundary | Recorded baseline result (scope limited to its original code, inputs and runtime) |
| --- | --- |
| Historical merged-main clean full reactor | `clean package`: 374 tests, 373 passed, zero failures/errors, one skipped because this Linux filesystem exposes no JDK ACL view. Model: 9; Indexer: 172; Source Query: 139; Document Query: 54. This predates latest-document-navigation. |
| Historical packaged native Document/local-publication proof | Three former-contract scenarios passed, zero skips: actual CLI/MCP, prior exact pins, rollback, refusal, withdrawal, and commit-success/selection-failure recovery. The POSIX permission fault used an unprivileged runner. Not evidence for format-2 owner maps, owner-scoped cursors, final-file removal, or new recovery semantics. |
| Historical packaged native Source/Relations proof | Two scenarios passed, zero skips, on the selected unchanged source runtime. Source retention/historical behavior remains a separate contract. |
| Historical local-document operator run | Explicit `gpt-6-luna`, Codex CLI 0.162.1: PASS_WITH_RECOVERY, including two setup detours. Preserved unrelated bytes and accurately reported `serviceVerified:false`; not latest-document operator acceptance. |
| Historical independent dual-MCP query | Explicit `gpt-6-luna`, Codex CLI 0.162.1: PASS, nine actual read-only calls with independently pinned source and raw evidence. This narrow run does not newly verify the latest owner-map behavior, historical/Relations behavior, or final-answer document-locator formatting. |
| Earlier broader document/navigation evidence | Results are limited to the recorded old inputs and scenarios; rejected answers remain failures. They do not establish latest-document-navigation acceptance. |
| Historical citation and Relations limits | Numbered source presentation has prior native proof, but the earlier module-aware guide's original model citation gate remains unaccepted. The accepted Relations Luna run preceded final-review production corrections; later ordinary/native checks do not constitute a new Luna execution. Its historical Query executable hash was not captured. |
| Review | Earlier feature reviews are historical review evidence, not acceptance of this milestone or deployment. |

The initial non-clean integration run recorded 17 obsolete generated test classes
whose constructors threw unresolved-compilation errors. Cleaning generated build
output resolved that failure; no production or test source was changed or skipped.
The freshly packaged `--document-local --help` also exited successfully with an
empty configuration/token environment. This help-path smoke is not running-service
adoption evidence. A prior prose-only cleanup passed local-reference checks and
read-only review; no new image, CI or external-agent acceptance was run for it.

Images, CI routing execution, deployed TLS, private production Git, supported-ACL
execution, separate-UID document reader deployment, idle-session/multi-user
capacity and production sizing remain unverified. Linux extended-POSIX-ACL-only
document namespaces are unsupported by the local command. TEST_ONLY documents
are not formal human approval or production publication. Elasticsearch and runtime
cross-repository graphs are not established by these results.

## Agent model for MCP query acceptance

Use **GPT 6 Luna** (`gpt-6-luna`) and select it explicitly, for example
`codex exec --model gpt-6-luna`. An unspecified client default is not selection
evidence. If unavailable, report a blocker and obtain approval before substitution.
This is a test-agent rule, not a service runtime model dependency.

Preserve client/version, actual invocation, requested model, any independently
reported provider model identity, complete MCP events and unedited final answer.
Never infer provider identity from the requested model. Historical unspecified-
model runs must not be relabeled as Luna acceptance.

Give the querying agent only Source Query read access, plus the independent
Document MCP read token for document navigation. Use disposable client settings;
no global login/config changes, local business-source/oracle tools, author trace
or preparation credentials. Source Query has no Git; Document Query uses its own
operator-managed central Git catalog. Controller Git-byte audits do not replace
the agent's actual MCP evidence. Reuse valid same-snapshot ordinary/native results
and exercise the changed agent scenario rather than rerunning unrelated gates.

## Select scope; separate tasks from feature acceptance

`scripts/verification-scope.sh` is the single lane selector. It selects checks;
it never starts Maven, Docker or services. From the reactor root:

```bash
bash scripts/verification-scope.sh --base <base-commit> --head <feature-head>
bash scripts/verification-scope.sh --full
bash scripts/test-verification-scope.sh
```

Output is exactly `unit=true|false`, `journey=true|false`, `images=true|false`
on three lines. Unknown paths or unavailable base/head/diff select all lanes.
Deletions and both sides of renames count; mixed changes take the union.

| Changed surface | Ordinary reactor | Native journey | Images |
| --- | --- | --- | --- |
| Only known prose: README.md, AGENTS.md, docs/**, skills/**/*.md | no | no | no |
| Root/module POMs, shared Model, CI or selector scripts | yes | yes | yes |
| Other Indexer/Query/Document main Java, excluding full-gate paths | yes | yes | no |
| App resources/config/security/bootstrap, origin/startup/source admission, document catalog/Git admission, index parser/SQLite/publication/read paths | yes | yes | yes |
| Ordinary test sources/helpers only | yes | no | no |
| SourceMcpJourneyIT, SourceRelationsJourneyIT, NativeJourneySupport, DocumentMcpJourneyIT, native journey launchers, video source corpus | no | yes | no |
| Dockerfiles, .env.example, image smoke launcher | no | no | yes |
| Unknown/unavailable diff or explicit full gate | yes | yes | yes |

Intermediate authorized tasks select the smallest affected native boundary and
reuse valid evidence. Workers do not run checks or commit; the controller owns
one integrated serial verification lane. Prose-only edits need guidance/link
checks, not Maven or containers. Selection never grants execution authority.

Final feature acceptance finishes tasks and review before authorized publication.
CI runs on non-draft PR `opened`, `reopened`, `synchronize`, `ready_for_review`,
not push/post-merge. Draft events may create workflows with skipped jobs. Optional
`workflow_dispatch` selects all lanes once registered on the default branch.
Each lane checks out the actual PR head; superseded runs cancel only within the
same PR/ref. The `source-journey` job runs Source then Document journeys serially.

`Feature acceptance` requires selected lanes to succeed and fails closed on
failure, cancellation or unexpected skip. Intentional docs-only three-lane skip
succeeds after selector regressions. Local selector checks do not prove GitHub
routing: ready feature PR and owned docs-only probe PR routing are separate
acceptance (close the probe without merge and retire only its branch). Branch
protection changes and publication require separate approval. Images run only
on an authorized isolated runner, not the local development loop.

## Prerequisites and launcher controls

| Boundary | Prerequisites and ownership |
| --- | --- |
| Scope selector | Bash, Git; temporary local Git only, no JVM/container/service |
| Ordinary suite | Java 21, Maven 3.9.x, Git, actual rg for source searches; SQLite JNI-capable libc/CPU and private writable/executable temp; no external DB or Docker |
| Source/Relations journey | Java 21, Maven 3.9.x, Git, absolute real ripgrep, SQLite JNI/private temp; independent packaged JVMs and disposable roots |
| Document journey | Java 21, Maven 3.9.x, Git; independent bounded JVMs/private Git and selection root; no rg/parser/SQLite requirement for this module |
| Images/smoke | Separately authorized Docker builder/engine, Git, curl, jq, jar, javac and Java 21/Maven 3.9.x; writer UID 10001, reader UID 10002 |
| Runtime storage | Separate admin/read/document tokens, Indexer-only source Git credentials, Query allowlist, private/source/index POSIX roots, per-process JNI temp; same-filesystem staging/publication |

Prefer installed system ripgrep. Do not commit an editor fallback, install/remove
packages or change global configuration as a workaround. `SOURCE_TEST_RG` maps
to Maven `source.test.rg`; ordinary actual-search tests accept that property.
`MAVEN_CMD` chooses launcher Maven. Production `SEMANTIC_QUERY_RG_EXECUTABLE`
is not the test control. Never source Indexer secrets into Source Query.

The Source launcher clean-packages the reactor, snapshots Indexer/Query executables,
and runs `SourceMcpJourneyIT,SourceRelationsJourneyIT` with `deployed-it`. It
removes only its disposable work/logs and retains response artifacts, not existing
`data/`. It does not automatically bound all JVMs; bound Maven, test forks and
services before admission. The Document launcher clean-packages its module,
snapshots the executable and runs `DocumentMcpJourneyIT,DocumentLocalPublicationJourneyIT`.
It defaults Maven to 384 MiB and test JVMs to 256 MiB, CPU count 2 and one fork;
it retains private work if child termination is unconfirmed, and retains sanitized
acceptance artifacts. No launcher proves external-agent or production acceptance.

## Focused command templates

Choose actual affected classes. These are command templates, not execution records.
Configure installed Maven 3.9.x/Java 21, real absolute rg and approved JVM bounds.
For example, after resource admission:

```bash
export SOURCE_TEST_RG=/absolute/path/to/rg
export MAVEN_OPTS='-Xms64m -Xmx512m -XX:ActiveProcessorCount=2'

mvn --batch-mode --no-transfer-progress -pl semantic-query -am \
  -Dtest=RipgrepTextSearchTest,SearchAdmissionTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dsource.test.rg="$SOURCE_TEST_RG" \
  '-DargLine=-Xms64m -Xmx512m -XX:ActiveProcessorCount=2' -DforkCount=1 test

mvn --batch-mode --no-transfer-progress -pl semantic-indexer -am \
  -Dtest=ApprovedOriginBindingTest,SourcePreparationPublicationTest,SourcePreparationRecoveryTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  '-DargLine=-Xms64m -Xmx512m -XX:ActiveProcessorCount=2' -DforkCount=1 test

mvn --batch-mode --no-transfer-progress -pl document-query -am \
  '-Dtest=Document*Test,MarkdownSectionsTest' -Dsurefire.failIfNoSpecifiedTests=false \
  '-DargLine=-Xms64m -Xmx256m -XX:ActiveProcessorCount=2' -DforkCount=1 test
```

Syntax/index-focused classes include `JavaSyntaxExtractorTest`,
`SqliteIndexArtifactTest`, `SemanticIndexRecoveryTest`, `IndexReadBehaviorTest`
and `IndexReadLifecycleTest`. They protect raw bytes, profiles/partial coverage,
SQLite sealing, receipt recovery, exact generations, cursor/byte limits and cleanup.
Also select affected dispatcher, unified admission, capacity, companion GC and
transport scenarios; naming classes is not proof they passed.

`failIfNoSpecifiedTests=false` permits upstream modules without named classes,
not a zero-test green. Report intended classes and actual scenario counts. Clean
after deleting Java/build outputs or at a fresh final gate, not every unchanged loop.

Capacity checks include `SourceCapacityGuardTest` and `SourceCapacityAdmissionTest`:
combined-root accounting, thresholds/boundaries and durable refusal without READY
replacement. Controlled volatile free-space readings are not filesystem quotas.
Packaged acceptance enables thresholds independently in owned isolated storage,
checks `FAILED/CAPACITY_EXCEEDED` via HTTP/MCP and reads prior exact bytes. After
explicit terminal failure and capacity correction, use a new saved request UUID;
keep the original failure recoverable. Never fill shared disks to induce failures.

Retention checks include `SourceRetentionPublicationTest`, `SourceReadLeaseTest`,
`SourceGarbageCollectorTest`, `SourceGcRecoveryTest` and affected publication/
transport paths. Use disposable roots/controlled clocks for 30-day boundaries,
not real-repository aging or a production test-clock flag. Metadata-write faults
are not power-loss/fsync proof. The native fixture ages only its stopped owned
Indexer's private retirement entry and checks withdrawal/stale-context parity.

## Resource and host/data safeguards

Admit at most one heavy local lane across all worktrees: no overlap of Maven,
journeys, Docker or another executor; no `-T` or extra parallel test forks.
`MAVEN_OPTS` alone does not bound forks, child workers or service JVMs.

Read `.local/workstation-safety.md` when present. Check Linux and actual backing-
host memory/swap/disk against operator-approved thresholds, not only virtual
filesystem free space. Missing metrics, unknown or insufficient headroom means
defer to an authorized isolated runner. Observe owned runs with bounded low-rate
sampling. On a stop threshold, stop admissions and gracefully cancel only the
owned verification process group; retain diagnostics and original request IDs.

Keep source admin, Source Query read and Document read credentials separate.
Source Query mounts only the two read-only published source/index children, never
Indexer Git/jobs/staging. JNI temp is process-private writable/executable storage.
Document Query's private Git/selection lifecycle is independent. Unknown acceptance
or child ownership means retain the failure root/request identity, not delete it.

Preserve unrelated services, container engines, clones, IDE tooling, caches and
data. Verification grants no global resource changes, shared pruning or unrelated
process termination. Retirement needs verified ownership/use and separate approval;
container-only approval does not authorize deleting mounts, volumes or host data.

## Verification commands

Run only selected, authorized lanes serially with bounded JVMs.

| Check | Command | Boundary |
| --- | --- | --- |
| Ordinary reactor | `mvn --batch-mode --no-transfer-progress -Dsource.test.rg="$SOURCE_TEST_RG" '-DargLine=-Xms64m -Xmx512m -XX:ActiveProcessorCount=2' -DforkCount=1 test` | Ordinary Model/Source/index/Document contracts; deployed journeys excluded |
| Source/Relations native | `SOURCE_TEST_RG=/absolute/path/to/rg scripts/test-source-mcp.sh` | Clean packaged real Git/rg/SQLite, separate JVMs, HTTP/MCP parity and raw source |
| Document native | `MAVEN_CMD=/absolute/path/to/maven-3.9/bin/mvn bash scripts/test-document-mcp.sh` | Future packaged per-owner CLI/map and stateless MCP checks: owner isolation, mixed hits/cursors, update/remove including final owner, empty map, same-operation recovery and Git bytes |
| Indexer image | `docker build -f Dockerfile.indexer -t java-source-indexer:acceptance .` | Writer artifact, parser/Symbol Solver/SQLite/private JNI |
| Source Query image | `docker build -f Dockerfile.query -t java-source-query:acceptance .` | Read-only source/index reader with rg/SQLite/private JNI |
| Started images | `QUERY_IMAGE=java-source-query:acceptance INDEXER_IMAGE=java-source-indexer:acceptance scripts/test-source-images.sh` | Actual UID/mount locks, source/Relations reads and GC; requires both builds first |

`SOURCE_TEST_RG` must be absolute, executable real ripgrep. Source journey treats
`semantic-indexer/fixtures/uat/video-service/` as source data, not a target build.
It copies only Git-tracked `pom.xml` and `src/main/` paths and uses working-tree
bytes for tracked edits. Stage added/deleted fixture paths before an authorized
journey; untracked reports, build outputs and nested Git metadata are excluded.
Framework imports intentionally remain unresolved; no resolution stubs or separate
fixture-Maven business build is required.

Relations native proof uses synthetic A/B profile-3 source without external
framework JARs: static precision/uncertainty, framework candidates, raw bytes,
refresh/rebuild pins, capacity preservation, generation expiry and cold Source
Query with Indexer stopped. It does not prove runtime dispatch, Kafka/Rabbit
approved-artifact interpretation or minimum superseded-generation TTL.

Image smoke checks actual packaged JavaParser/Symbol Solver 3.28.2 and SQLite
3.53.4.0, nine Source Query/three Indexer tools, child-built profile-3 generations,
private JNI, retained/rebuilt A/B and source independence under native failure.
Its JDK test peer takes a shared lock as UID 10002 on the read-only mount: this is
OS/UID/mount proof, not an in-flight HTTP request. Only the disposable image
fixture shortens the retention retry interval to `1s`; production default is `24h`.
Written image checks are not observed image success; there is no Document image.

## Verification evidence and deployment limits

Record exact tested source/artifact/skill identities, commands, results, skipped
checks and environment. Reuse evidence only for unchanged relevant inputs and
snapshot; a baseline SHA alone does not identify dirty tested changes. Retain full
MCP text and structured results, errors/item statuses, raw answers and rejected
attempts privately. Controller byte/line and semantic audits are independent of
agent self-report; CLI exit zero or nonempty Markdown is not acceptance.

Capacity measurements identify workload, sample interval, owned processes and
logical/allocated bytes. RSS can double-count shared pages and miss child peaks;
small fixtures are not production sizing, hard caps, latency SLA or 50-user proof.
An event audit showing no local-source bypass is not filesystem sandbox isolation.

Source Query uses the [MCP session lifecycle](source-mcp.md#mcp-session-lifecycle);
abandoned sessions remain until shutdown. Document MCP is independent and stateless.
Native SDK/socket results do not establish external OMP/Codex/Claude use, private
production access, TLS, idle-session or multi-user capacity. Follow the
[agent acceptance checklist](mcp-agent-acceptance.md) for model reasoning, exact
citations, isolation and recovery classification.

## Release checks and boundaries

Before authorized deployment, verify allowlists/token separation, private admin
TLS ingress, fresh public format-1/policy-2 source namespaces, stable read locks,
same-filesystem atomic publication and actual UID/mount isolation. Check pagination,
UTF-8/CRLF/long-line continuations, bounded literal rg, exclusions/unsupported paths,
guide status, A/B exact reads and failure preservation. Check retirement/republication,
busy-read deferral, durable deletion recovery, disabled GC and stale-context denial.
Source Query must read published revisions after Indexer stops; registration or an
orphan directory never authorizes source reads.

Check Source/Indexer wire contracts and independent Document wire contracts;
an HTTP-only response or skipped test is not MCP/model acceptance. Document
release checks actual per-owner map adoption, mixed-owner document/source pins,
owner-scoped versus global cursors, last-good rejection, named/final removal,
same-operation recovery and supported reader permissions. Source retention is
separate and unchanged by this milestone. Formal document approval/publication
and deployment capacity require their own authorization and evidence. Recovery,
startup and rollback contracts live in
[Source MCP operations](source-mcp.md) and [Document MCP operations](document-mcp.md).
