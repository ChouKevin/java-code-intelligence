# Testing and verification

Run from the reactor root. These checks exercise current Source-first contracts,
**not** target-repository business builds, agent/model reasoning, production TLS
or capacity. [Roadmap Phase 2–6](../roadmap.md) features/dependencies/workloads
are not prerequisites for this cleanup or Phase 1 verification.

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
| Indexer/Query main Java | yes | yes | no |
| App resources/config/security/bootstrap, source admission/path authorization | yes | yes | yes |
| Ordinary test sources/helpers only | yes | no | no |
| SourceMcpJourneyIT, native journey launcher, video source corpus | no | yes | no |
| Dockerfiles, .env.example, image smoke launcher | no | no | yes |
| Unknown/unavailable diff or explicit full gate | yes | yes | yes |

**Intermediate tasks:** use the smallest affected native test or changed-path
smoke and local checkpoint commits. No CI or image build/smoke, locally or
remotely; no intermediate push or ready PR. Reuse valid same-snapshot evidence.
The table selects coverage, not permission to run full gates after every task.

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

Run selected images on isolated CI, not in the default local loop. This cleanup
changes CI, so its final integrated gate selects reactor, journey and images.
Diagnose failed gates and verify the corrected snapshot; never infer success
from a script existing or a gate being scheduled.

## Prerequisites and launcher controls

| Boundary | Required | Not required / ownership |
| --- | --- | --- |
| Scope selector regressions | Bash and Git | Temporary local Git only; no JVM/container/services |
| Ordinary native tests | Java 21, Maven 3.9.x; Git for local preparation tests; real rg for actual search tests | No Docker, MongoDB, JDT LS or PostgreSQL service |
| Native MCP journey | Java 21, Maven 3.9.x, Git, configured absolute executable real ripgrep | Independent JVMs and disposable roots; no target-repository builds |
| Final image builds/smoke | Isolated CI Docker engine/builder, Git, curl, jq, jar; Java 21/Maven 3.9.x for the CI environment | Non-root writer UID 10001 / reader UID 10002; only the published child is mounted read-only by Query |
| Current Source-first runtime | Separate Indexer admin / Query read tokens, Indexer-only Git credentials, Query allowlist, new private/published POSIX roots | No Mongo/JDT service; keep unrelated local PostgreSQL and Docker/data running |

Prefer a standard system ripgrep installation. Do not commit a workstation/editor
fallback, install/uninstall packages or change global settings as a test workaround.
`SOURCE_TEST_RG` is a test launcher control, mapped to Maven `source.test.rg`;
ordinary actual-search tests also accept that Maven property directly.
`MAVEN_CMD` selects the native journey's Maven executable.
`SEMANTIC_QUERY_RG_EXECUTABLE` configures production Query; it is not a replacement
for the test control. Do not source Indexer secrets into Query.

### Focused local commands

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

`failIfNoSpecifiedTests=false` permits upstream modules without the named class;
it does not permit a zero-test green. Identify the intended classes and actual
scenario counts in the report. Clean after deleting Java/build outputs or at a
fresh final gate, not every unchanged loop.

### Resource and host/data safeguards

Allow only one heavy local verification lane across all worktrees. Do not overlap
Maven, real journey, Docker builds/smoke or another executor; no Maven `-T` or extra
parallel test forks. Journey service JVMs belong to that one lane. `MAVEN_OPTS`
does not by itself bound test forks or launched service JVMs.

Before admission, require WSL `MemAvailable` and Windows free physical RAM each
at least 6 GiB, swap used at most 256 MiB with no sustained swap-in/out in a short
sample, and at least 20 GiB free on both Linux and the actual Windows volume
holding data/swap. Missing metrics or insufficient headroom means defer to an
admitted lane/CI, not retry under pressure. Virtual ext4 capacity is not additional
physical SSD capacity. Observe only owned runs with bounded low-rate sampling;
if either available RAM falls below 2 GiB or swap grows 512 MiB within 30 seconds,
stop admitting work and gracefully cancel the owned verification process group.
Retain diagnostics/original request IDs; never terminate unrelated IDE/services.

Preserve separate admin/read tokens, Indexer-only credentials/private jobs/staging,
immutable published roots, Query allowlist and read-only published-child mount.
Image smoke retains its failure root/request ID after unknown acceptance outcomes;
native journey removes its disposable work/logs and retains response artifacts.
Neither script authorizes deleting existing managed roots or user service data.

Keep local PostgreSQL (including `java-agent-uat-postgres-1`), its Docker engine
and data running. Do not stop Docker/WSL, change global limits or prune containers,
images, volumes or caches. Any named legacy container retirement needs separate
owner confirmation; unresolved ownership/use means leave it intact. Container-only
approval does not approve deleting mounts/data/volumes, clones or IDE tooling.

## Verification commands

| Check | Command | What it covers |
| --- | --- | --- |
| Ordinary native suite | `mvn --batch-mode --no-transfer-progress test` | Model, Indexer and Query source behavior, HTTP/MCP contracts, security and architecture; no external service |
| Real native MCP source journey | `SOURCE_TEST_RG=/absolute/path/to/rg scripts/test-source-mcp.sh` | Clean package, real temporary Git A/B, independent Indexer and Query JVMs, native SDK and HTTP, original-request lookup, warm/cold exact source read |
| Indexer image | `docker build -f Dockerfile.indexer -t java-source-indexer:phase1 .` | Source-only writer artifact |
| Query image | `docker build -f Dockerfile.query -t java-source-query:phase1 .` | Source-only reader image with ripgrep |
| Started-image smoke | `QUERY_IMAGE=java-source-query:phase1 INDEXER_IMAGE=java-source-indexer:phase1 scripts/test-source-images.sh` | Fresh Git preparation; Query published-only read-only mount; distinct non-root UID and actual search/read |

`SOURCE_TEST_RG` must point to a real executable ripgrep at an **absolute path**; when `/usr/bin/rg` is unavailable set the variable explicitly. The journey script checks Java 21, Git and rg, creates a new `mktemp` root, packages clean executable jars, runs only `SourceMcpJourneyIT` using the `deployed-it` profile, removes **only its disposable** Git/service roots and reports its artifact directory. It does not clear existing `data/`. Image smoke needs Docker, Git, curl, jq and jar and likewise uses a disposable root. Run the two image builds before image smoke; building images alone does not establish mount isolation.

The journey treats `semantic-indexer/fixtures/uat/video-service/` as source data,
not a business project to build. It copies only Git-tracked `pom.xml` and
`src/main/` paths, then generates the existing order/payment guides in its disposable
repository. Stage newly added video fixture files before running the local journey;
tracked file edits use their working-tree bytes. Untracked reports, build outputs,
test inputs and nested Git metadata are not fixture inputs. The order/payment
business projects, unused version patch and standalone fixture-Maven gate are retired.

Do not reuse old Mongo/JDT, semantic-review or Git-review acceptance reports as Source MCP results. A successful native SDK journey is not evidence of OMP/Codex/Claude model use, private Git coverage, TLS or 50-user load. The [agent checklist](mcp-agent-acceptance.md) separates those layers.

## Verification evidence and deployment limits

Historical acceptance and repair results remain in [PR #2 and its CI runs](https://github.com/ChouKevin/java-code-intelligence/pull/2),
with local experiment details in the repair ledger. They are checkpoint evidence,
not a requirement to replay every historical scenario for every change. Choose
checks for the changed boundary; do not present older results as new verification.

Query requires the [MCP session lifecycle](source-mcp.md#mcp-session-lifecycle).
The pinned provider retains abandoned sessions until shutdown. Native SDK,
socket and image checks do not establish idle-session capacity, external
OMP/Codex/Claude model-client acceptance, private-production Git access, deployed
TLS, or 50-user capacity. Image/mount checks and external release-environment
acceptance remain separate from native source verification.

## Release checks and boundaries

Before deployment check explicit repository allowlist/secret separation, private admin TLS ingress, matching format/policy-1 namespace and same-filesystem atomic publication. Verify direct-child pagination/cursors, UTF-8/CRLF/long-line read continuation, bounded literal rg, unsupported/excluded paths, guide hint status, A/B exact reads and failure-preserves-A. Query must remain readable on a published revision after Indexer stops; an orphan directory or registered-but-unprepared repo is **not** an admissible source. Verify the actual client against both `/mcp` endpoints independently. Neither a skipped test nor an HTTP-only response proves MCP/model acceptance. See [Source MCP operations](source-mcp.md) for startup, original-request recovery and rollback.
