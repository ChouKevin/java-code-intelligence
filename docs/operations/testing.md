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

## Observed local evidence (2026-10-04 snapshot)

The controller observed the final source-only ordinary reactor of **77 passing tests** (Model 6, Indexer 24, Query 47; zero failures/errors/skips and no compiler warnings) with a real ripgrep path supplied because host `/usr/bin/rg` is absent. The final clean `scripts/test-source-mcp.sh` passed with real native SDK, HTTP, local Git/rg, separate Indexer and warm/cold Query processes. Retained **disposable-run** evidence is `/tmp/source-mcp-journey.eOnMWnaJ/artifacts/` (ephemeral local path, not a committed fixture or reproducibility guarantee). `revisions.json` records A `cf2139c66206117da6582a40cc88b510abca8cbe`, B `9ec17c7cc1ed68936d12ac34410722672d08c862`; `job-a.json` records original request `4db7b2fb-702e-46e0-b97e-31b42c2d4c80` and durable COMPLETE identity. A remains unchanged after B and after known-SHA republishing, with manifest/inventory/tree hashes checked. Every actually returned fixture repo/SHA/path/line citation is compared to the exact Git blob. Cold Query reads A/B after Indexer stops and disposable remote/private paths become unavailable. Only intentional JSON response/request/provenance artifacts are retained; raw Git/process logs are not copied.

Both matching source images were built after the final fixes, and the final started-container `scripts/test-source-images.sh` passed real Git preparation, actual ripgrep search/read, Query child-only read-only mount, distinct UID 10001 writer/10002 reader, and absence of Query private credentials/mount. Nondefault published-root/rg environment paths work through the real application; an invalid rg path fails startup. Final command/output evidence: ordinary reactor `artifact://268`, clean journey `artifact://271`, focused authorization/recovery/search gate `artifact://262` (25 passing tests), Indexer image `artifact://260`, Query image `artifact://261`, and successful final image smoke `bg60`. The whole-branch four-finding fix wave is implemented and runtime-verified; its scoped final re-review remains a separate acceptance gate. These evidence paragraphs were updated after the observed checks, not used to infer them.

An additional actual ENOSPC smoke exhausted only a fresh owned 32 MiB Docker tmpfs (free bytes 0): B stayed pinned/RUNNING while storage was full, then lookup of the **same** request after restart returned FAILED without replay; A remained readable and all revision file hashes unchanged, and B context was denied. Only the created containers/named tmpfs volume were removed; no existing service data or shared host filesystem was filled. Disposable evidence: `/tmp/source-enospc-ov9k_hos/evidence.json`. The same 15,000-file/100-match real-rg probe changed from SOURCE_TIMEOUT at 4,761 ms to the correct 100-match complete result at 1,111 ms. A real rejected-legacy-config socket probe returned exact pinned source/HTTP 200 before the fix; after the before-bind gate it rejected startup without opening the source-serving web server. Native regressions preserve terminal status convergence, latest-job safety and authorized result limits.

## Release checks and boundaries

Before deployment check explicit repository allowlist/secret separation, private admin TLS ingress, matching format/policy-1 namespace and same-filesystem atomic publication. Verify direct-child pagination/cursors, UTF-8/CRLF/long-line read continuation, bounded literal rg, unsupported/excluded paths, guide hint status, A/B exact reads and failure-preserves-A. Query must remain readable on a published revision after Indexer stops; an orphan directory or registered-but-unprepared repo is **not** an admissible source. Verify the actual client against both `/mcp` endpoints independently. Neither a skipped test nor an HTTP-only response proves MCP/model acceptance. See [Source MCP operations](source-mcp.md) for startup, original-request recovery and rollback.
