# Testing and verification

Run from the reactor root with Java 21, Maven 3.9+, Git and a real absolute-path ripgrep executable for source journeys. Ordinary tests need no MongoDB, Docker or JDT LS. Use Docker only for image/build isolation checks. These commands exercise source contracts, **not** target repository tests, agent/model reasoning, production TLS or capacity.

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
