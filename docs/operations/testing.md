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

The controller observed a source-only ordinary reactor of **69 passing tests** (Model 6, Indexer 22, Query 41; zero failures/errors/skips) with a real ripgrep path supplied because host `/usr/bin/rg` is absent. `scripts/test-source-mcp.sh` passed once with real native SDK, HTTP, local Git/rg, separate Indexer and warm/cold Query processes; the retained **disposable-run** evidence is `/tmp/source-mcp-journey.qcRjDSJk/artifacts/` (ephemeral local path, not a committed fixture or reproducibility guarantee). Its `revisions.json` records A `d366278ff697b2ac7d0510830a840a475ec31794`, B `6ef8d1691463009a94b4373c321a6f07f4d0c515`; `job-a.json` contains the original request ID and durable COMPLETE identity. `video-a-provenance.json` and `video-b-provenance.json` contain the **actually returned** repo/SHA/path/line evidence. Warm and cold responses record A unchanged after B; the Query restart ran without the Indexer or disposable remote.

Both source images were built, and the started-container `scripts/test-source-images.sh` passed real Git preparation, actual ripgrep search/read, Query child-only read-only mount, distinct UID 10001 writer/10002 reader, and absence of Query private credentials/mount. **That first image smoke predates the latest reader fix**; its result is not evidence for the final image snapshot. The controller owns the final rerun, any subsequent runner-fix verification and review; this documentation does **not** mark those pending gates complete. The doc-only change did not rerun Maven, journeys, images or tests. Full command/output artifacts and final acceptance should be reported by the controller against the final snapshot rather than inferred from this paragraph.

## Release checks and boundaries

Before deployment check explicit repository allowlist/secret separation, private admin TLS ingress, matching format/policy-1 namespace and same-filesystem atomic publication. Verify direct-child pagination/cursors, UTF-8/CRLF/long-line read continuation, bounded literal rg, unsupported/excluded paths, guide hint status, A/B exact reads and failure-preserves-A. Query must remain readable on a published revision after Indexer stops; an orphan directory or registered-but-unprepared repo is **not** an admissible source. Verify the actual client against both `/mcp` endpoints independently. Neither a skipped test nor an HTTP-only response proves MCP/model acceptance. See [Source MCP operations](source-mcp.md) for startup, original-request recovery and rollback.
