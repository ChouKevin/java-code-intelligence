# Java Code Intelligence

Java Code Intelligence is an offline semantic-index service with two independently deployable applications:

- **Indexer** accepts private administrative commands, reads Git repositories, runs JDT LS, and publishes immutable MongoDB generations.
- **Query** is read-only. It serves the current sealed MongoDB generation through HTTP and MCP; it never opens a repository, starts JDT LS, or performs index mutation.

The deterministic fixture pipeline indexes the payment, order, and video services through the same production exporter used for all repositories. There is no LLM, chat, prompt, or embedding model in the applications or their runtime configuration.

## Build and verification

Requirements: Java 21, Maven 3.9+, Docker for Mongo/image checks, and JDT LS only for the Indexer smoke and end-to-end fixture checks.

```bash
mvn --batch-mode --no-transfer-progress clean test
mvn --batch-mode --no-transfer-progress -pl semantic-indexer,semantic-query -am -Pmongo-it test
mvn --batch-mode --no-transfer-progress -f semantic-indexer/fixtures/uat/payment-service/pom.xml test
mvn --batch-mode --no-transfer-progress -f semantic-indexer/fixtures/uat/order-service/pom.xml test
mvn --batch-mode --no-transfer-progress -f semantic-indexer/fixtures/uat/video-service/pom.xml test
docker build -f Dockerfile.indexer -t java-semantic-indexer:uat .
docker build -f Dockerfile.query -t java-semantic-query:uat .
scripts/smoke-jdtls-image.sh java-semantic-indexer:uat
scripts/test-query-image.sh java-semantic-query:uat
JDTLS_HOME=/opt/jdtls scripts/test-indexer-query-contract.sh
```

The full operational runbook is [offline-index.md](docs/operations/offline-index.md).

## Credentials and endpoints

Set separate credentials for each application. `SEMANTIC_INDEXER_ADMIN_TOKEN` authorizes only Indexer administration endpoints; `SEMANTIC_QUERY_API_TOKEN` authorizes Query HTTP and MCP reads. Never share these tokens or grant Query a Git credential.

```bash
export SEMANTIC_MONGODB_URI='mongodb://query-reader:...@mongo/semantic?tls=true'
export SEMANTIC_INDEXER_ADMIN_TOKEN='<indexer-admin-token>'
export SEMANTIC_QUERY_API_TOKEN='<query-read-token>'
export JDTLS_HOME=/opt/jdtls
export GIT_USERNAME='<read-only-git-user>'
export GIT_TOKEN='<read-only-git-token>'
```

The Indexer admin API is rooted at `/index/repositories/{repoId}` and accepts asynchronous `ensure`, `sync`, `checkout`, `rebuild`, and `rollback` commands. Query exposes only read HTTP and the stateless `/mcp` transport. Query requests specify `repositoryId` and an exact `revision`; the service reads only a sealed generation selected by the repository pointer.

## Compatibility boundary

The pre-split, single-image cutover can be reproduced only from pinned Git revision `9ff90b7e51eafbb6ef954e4ae0cc8a6a22173f9c` (the parent of split commit `2f1d0e8deadbd552b6ef2b5253a4252debe9631f`). No current CI, deployment, or runtime path uses a legacy image, compatibility file, or unpinned revision.
