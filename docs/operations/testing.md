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

The controller observed the final source-only ordinary reactor of **71 passing tests** (Model 6, Indexer 22, Query 43; zero failures/errors/skips and no compiler warnings) with a real ripgrep path supplied because host `/usr/bin/rg` is absent. The final clean `scripts/test-source-mcp.sh` passed with real native SDK, HTTP, local Git/rg, separate Indexer and warm/cold Query processes. Retained **disposable-run** evidence is `/tmp/source-mcp-journey.y7ZpBb6y/artifacts/` (ephemeral local path, not a committed fixture or reproducibility guarantee). `revisions.json` records A `dfa0b346cafdb0b01e2a48d84c753a4e11961cf6`, B `0c17f27bff64b8499fca16098080fd96f36efc79`; `job-a.json` records original request `47c4dc9a-1ab3-4197-94dc-288abfd3ea80` and durable COMPLETE identity. A remains unchanged after B and after known-SHA republishing, with manifest/inventory/tree hashes checked. Every actually returned fixture repo/SHA/path/line citation is compared to the exact Git blob. Cold Query reads A/B after Indexer stops and disposable remote/private paths become unavailable. Only intentional JSON response/request/provenance artifacts are retained; raw Git/process logs are not copied.

Both matching source images were built after the final fixes, and the final started-container `scripts/test-source-images.sh` passed real Git preparation, actual ripgrep search/read, Query child-only read-only mount, distinct UID 10001 writer/10002 reader, and absence of Query private credentials/mount. Nondefault published-root/rg environment paths work through the real application; an invalid rg path fails startup. Final command/output evidence: ordinary reactor `artifact://224`, clean journey `artifact://228`, Indexer image `artifact://214`, Query image `artifact://223`, and successful final image smoke `bg51`. Scoped journey/Query fix reviews are clean; whole-branch acceptance remains a separate final review gate. These evidence paragraphs were updated after the observed checks, not used to infer them.

## Release checks and boundaries

Before deployment check explicit repository allowlist/secret separation, private admin TLS ingress, matching format/policy-1 namespace and same-filesystem atomic publication. Verify direct-child pagination/cursors, UTF-8/CRLF/long-line read continuation, bounded literal rg, unsupported/excluded paths, guide hint status, A/B exact reads and failure-preserves-A. Query must remain readable on a published revision after Indexer stops; an orphan directory or registered-but-unprepared repo is **not** an admissible source. Verify the actual client against both `/mcp` endpoints independently. Neither a skipped test nor an HTTP-only response proves MCP/model acceptance. See [Source MCP operations](source-mcp.md) for startup, original-request recovery and rollback.
