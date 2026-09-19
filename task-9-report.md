# Task 9 semantic review evidence

## Executed proof

- `JDTLS_HOME=/opt/jdtls .superpowers/sdd/2026-09-19-current-to-commit-semantic-review/tools/apache-maven-3.9.11/bin/mvn --batch-mode --no-transfer-progress -pl semantic-indexer -am -Pjdtls-it -Dtest=SemanticReviewJdtLsIT -Dsurefire.failIfNoSpecifiedTests=false test`
  completed with one passing real-JDT test. The disposable Git repository contains distinct legacy and modern implementations, callers, references, method source, a changed method signature, custom source roots, changed compiled dependency jars, and an HTTP route. The test also proves a direct ancestor comparison, direct divergent comparison, review pinning while current moves, an authorized reserved rebuild, and the equal-SHA case: a reused generation has two distinct published snapshot IDs and a direct empty A-to-B comparison.
- `JDTLS_HOME=/opt/jdtls MAVEN_CMD=.superpowers/sdd/2026-09-19-current-to-commit-semantic-review/tools/apache-maven-3.9.11/bin/mvn scripts/test-semantic-review-journey.sh`
  completed with one passing opt-in external-process journey. It packages fresh Indexer and Query jars, creates a private authenticated Mongo database with a read-only Query user, proves that user cannot write, indexes A with JDT LS, submits only B, rejects the read token at the Indexer admin endpoint, then stops Indexer and moves the remote, seed, checkout, and JDT workspace away. The independently started Query jar runs from a temporary directory with only the Mongo read URI and serves review source, relations, and Git patches over real HTTP and MCP.

## Root causes retained

- Mongo analysis input compiler-option maps use dotted keys. Both Indexer and Query configure the Mongo converter key replacement, so the Indexer can persist real JDT evidence and the cold Query can read it.
- JDT LS reports `SUCCEED`, while the persisted analysis evidence contract requires `SUCCESS`; the preparation layer normalizes it and stores non-optional limitations in a Mongo-compatible representation.
- Review reuse obtains real effective JDT inputs through the existing workspace manager, so compatible equal-SHA generations are reusable while authorized rebuilds reserve a new generation.
- Source-root validation ignores configured roots with no persisted source files and normalizes the repository root correctly, which permits real custom-root fixture evidence without weakening validation.
- A ready review always retains distinct immutable Git snapshot IDs, including equal-SHA A-to-B reviews. It no longer requires structurally equal endpoints merely because their generations or revisions match.

## External limits

The journey requires Java 21, Docker, `/opt/jdtls`, and the bundled Maven 3.9.11 path. It intentionally is not part of the ordinary Docker- and JDT-free suite. The test uses temporary ports, repositories, process logs, Mongo users, and containers only; it does not contact a deployment or retain any fixture data after completion.
