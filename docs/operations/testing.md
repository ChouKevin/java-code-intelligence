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

The existing fixture projects can be checked separately if needed (these tests exercise their own fixture behavior, not source-service acceptance):

```bash
mvn --batch-mode --no-transfer-progress -f semantic-indexer/fixtures/uat/payment-service/pom.xml test
mvn --batch-mode --no-transfer-progress -f semantic-indexer/fixtures/uat/order-service/pom.xml test
mvn --batch-mode --no-transfer-progress -f semantic-indexer/fixtures/uat/video-service/pom.xml test
```

Do not reuse old Mongo/JDT, semantic-review or Git-review acceptance reports as Source MCP results. A successful native SDK journey is not evidence of OMP/Codex/Claude model use, private Git coverage, TLS or 50-user load. The [agent checklist](mcp-agent-acceptance.md) separates those layers.

## PR #2 repair verification (2026-10-04)

After the client-isolation, search-admission, registry-snapshot and MCP lifecycle
repairs, the controller ran the complete Java 21 reactor: **91 tests passed**
(Model 6, Indexer 29, Query 56; zero failures/errors/skips). The run supplied a real
ripgrep executable through `-Dsource.test.rg`; no workstation fallback was added.
The clean `scripts/test-source-mcp.sh` also passed with real Git/ripgrep,
independent Indexer and warm/cold Query processes, native MCP/HTTP parity, and
exact A/B source citations.

A separate actual-socket probe initialized two Query sessions and verified that
both clients could use the same in-flight JSON-RPC ID; a concurrent duplicate
inside one session was rejected without interrupting its owner. Foreign
cancellation left the other client's real search intact; owner cancellation
returned a structured `SOURCE_TIMEOUT`, reaped its subprocess, and allowed later
searches. Two real searches across the sessions caused a third request to return
`SOURCE_BUSY`. DELETE completed only after its child was reaped; Query shutdown
also reaped an active child before JVM exit.

Deterministic reactor regressions proved that the permit precedes catalog
admission, a registry replacement cannot bind an old page to the new digest,
DELETE/graceful close wait while terminal cleanup holds the search permit, and
cancelled tool responses survive interrupt-sensitive servlet I/O. The defect
regressions had observed behavioral failures before their production fixes.

Deadline-exhaustion coverage holds cleanup past the lifecycle budget and verifies
that graceful close fails without starting a second cleanup waiting period.

Query now requires the [MCP session lifecycle](source-mcp.md#mcp-session-lifecycle).
The pinned provider retains abandoned sessions until shutdown; this verification
does not establish idle-session capacity, external model-client acceptance,
private-production Git access, or deployed TLS. Image/deployment gates remain
separate from the local socket evidence.

## Initial Phase 1 evidence before PR #2 review (2026-10-04)

The controller observed the final source-only ordinary reactor of **84 passing tests** (Model 6, Indexer 29, Query 49; zero failures/errors/skips and no compiler warnings) with a real ripgrep path supplied because host `/usr/bin/rg` is absent. The final clean `scripts/test-source-mcp.sh` passed with real native SDK, HTTP, local Git/rg, separate Indexer and warm/cold Query processes. Retained **disposable-run** evidence is `/tmp/source-mcp-journey.Qhy1JQFv/artifacts/` (ephemeral local path, not a committed fixture or reproducibility guarantee). `revisions.json` records A `395566e8f884883d147020d42c21b4b281185a41`, B `c8acbff5042091b9f36c8d4eb2b3296b3e4f82c7`; `job-a.json` records original request `9e86e823-5689-4ac9-ba37-4a58e33c1611` and durable COMPLETE identity. A remains unchanged after B and after known-SHA republishing, with manifest/inventory/tree hashes checked. Every actually returned fixture repo/SHA/path/line citation is compared to the exact Git blob. Cold Query reads A/B after Indexer stops and disposable remote/private paths become unavailable. Only intentional JSON response/request/provenance artifacts are retained; raw Git/process logs are not copied.

Both matching source images were built after the final fixes, and the final started-container `scripts/test-source-images.sh` passed real Git preparation, actual ripgrep search/read, Query child-only read-only mount, distinct UID 10001 writer/10002 reader, and absence of Query private credentials/mount. Nondefault published-root/rg environment paths work through the real application; an invalid rg path fails startup. Final command/output evidence: ordinary reactor `artifact://297`, clean journey `artifact://300`, focused authorization/recovery/search gate `artifact://292` (32 passing tests), Indexer image `artifact://290`, Query image `artifact://291`, and successful final image smoke `bg72`. The whole-branch review's four original findings and both dependent residual findings are CLOSED; the final dependent review of production checkpoint `bc1bd3d` returns no findings and the controller accepts the Phase 1 implementation. Reviewer execution was read-only; runtime evidence is controller-observed. Main integration and external release-environment checks remain separate decisions. These evidence paragraphs were updated after the observed checks, not used to infer them.

An additional actual ENOSPC smoke exhausted only a fresh owned 32 MiB Docker tmpfs (free bytes 0): B stayed pinned/RUNNING while storage was full, then lookup of the **same** request after restart returned FAILED without replay; A remained readable and all revision file hashes unchanged, and B context was denied. Only the created containers/named tmpfs volume were removed; no existing service data or shared host filesystem was filled. Final disposable evidence: `/tmp/source-enospc-p6hdofy7/evidence.json`. The real 15,000-file/100-authorized-match probe now returns the correct complete result at 829 ms. The harder 15,000-tracked/1,000-untracked-prefix limit-one probe changed from SOURCE_TIMEOUT at 4,762 ms and repeated complete inventory reads to the correct authorized prefix at 1,707 ms; native JDK FileRead tracing records exactly 3,045,000 bytes in the full verification stack, matching the inventory size once. A nonroot `src` variant returns the correct prefix at 1,982 ms with one full 3,105,098-byte verification. Remaining reads are bounded positional seeks, not repeated digest/order passes. A real rejected-legacy-config socket probe returned exact pinned source/HTTP 200 before the fix; after the before-bind gate it rejected startup without opening the source-serving web server. Native reversed-audit-clock and reservation-gap/corrupt-order scenarios preserve durable acceptance and newest terminal status without timestamp ordering or new public job fields.

## Release checks and boundaries

Before deployment check explicit repository allowlist/secret separation, private admin TLS ingress, matching format/policy-1 namespace and same-filesystem atomic publication. Verify direct-child pagination/cursors, UTF-8/CRLF/long-line read continuation, bounded literal rg, unsupported/excluded paths, guide hint status, A/B exact reads and failure-preserves-A. Query must remain readable on a published revision after Indexer stops; an orphan directory or registered-but-unprepared repo is **not** an admissible source. Verify the actual client against both `/mcp` endpoints independently. Neither a skipped test nor an HTTP-only response proves MCP/model acceptance. See [Source MCP operations](source-mcp.md) for startup, original-request recovery and rollback.
