# Java Code Intelligence

Java Code Intelligence builds a semantic index before query traffic arrives. It has two independently deployable applications:

- **Indexer** accepts private admin commands, checks out an exact Git commit, runs JDT LS, and publishes immutable MongoDB generations.
- **Query** reads the current sealed generation through HTTP and MCP. It has no Git checkout, source fallback, JGit, JDT, JDT LS, or model dependency.

There is no LLM, chat, prompt, embedding, or vector model inside this service.

## Index flow

An Indexer request resolves a branch, tag, or full SHA to a reachable lowercase 40-character commit and stores a job. The HTTP request never runs a build or reset inline. One `index-job-dispatcher` thread polls the oldest `ACCEPTED` job, marks it `RUNNING`, and executes one job at a time.

The only poll setting is:

```yaml
semantic:
  index-jobs:
    poll-delay: 1s
```

A build checks out only its stored commit, removes untracked and ignored checkout content, runs the exporter, validates the new generation, seals it, and changes the repository pointer with an expected-parent compare-and-set. Query reads only that pointer and its sealed generation. Every successful source response includes the published repository revision.

Job failures use stable categories: `WORKER_INTERRUPTED`, `SOURCE_UNAVAILABLE`, `SCHEMA_REBUILD_REQUIRED`, `PUBLICATION_CONFLICT`, and `VALIDATION_FAILED`. Retry means submitting a new job; the dispatcher does not retry automatically.

At startup, Indexer first completes any `RUNNING` job whose target was already published, then marks all other leftover `RUNNING` jobs as `WORKER_INTERRUPTED`. Polling begins after application startup is ready. Shutdown stops new polling and interrupts current dispatcher work; a restart applies the same recovery rules.

## Build and verification

Requirements: Java 21, Maven 3.9+, Docker for Mongo and image checks, and JDT LS for Indexer smoke and end-to-end fixture checks.

```bash
mvn --batch-mode --no-transfer-progress test
mvn --batch-mode --no-transfer-progress -Pmongo-it verify
mvn --batch-mode --no-transfer-progress -f semantic-indexer/fixtures/uat/payment-service/pom.xml test
mvn --batch-mode --no-transfer-progress -f semantic-indexer/fixtures/uat/order-service/pom.xml test
mvn --batch-mode --no-transfer-progress -f semantic-indexer/fixtures/uat/video-service/pom.xml test
docker build -f Dockerfile.indexer -t java-semantic-indexer:uat .
docker build -f Dockerfile.query -t java-semantic-query:uat .
scripts/smoke-jdtls-image.sh java-semantic-indexer:uat
scripts/test-query-image.sh java-semantic-query:uat
JDTLS_HOME=/opt/jdtls scripts/test-indexer-query-contract.sh
```

See [Offline Index Operations](docs/operations/offline-index.md) for deployment and recovery details.

## Credentials and endpoints

Use separate identities. `SEMANTIC_INDEXER_ADMIN_TOKEN` protects `/index/**`; `SEMANTIC_QUERY_API_TOKEN` protects Query HTTP and MCP. Indexer receives read-only Git credentials and a Mongo write role. Query receives only a Mongo read role and must not receive Git or JDT credentials.

```bash
# Indexer
export SEMANTIC_MONGODB_URI='mongodb://index-writer:...@mongo/semantic?tls=true'
export SEMANTIC_INDEXER_ADMIN_TOKEN='<indexer-admin-token>'
export JDTLS_HOME=/opt/jdtls
export GIT_USERNAME='<read-only-git-user>'
export GIT_TOKEN='<read-only-git-token>'

# Query
export SEMANTIC_MONGODB_URI='mongodb://query-reader:...@mongo/semantic?tls=true'
export SEMANTIC_QUERY_API_TOKEN='<query-read-token>'
```

Configure every repository with a Git `url` and `defaultBranch`. Indexer admin endpoints under `/index/repositories/{repoId}` accept asynchronous `ensure`, `sync`, `checkout`, `rebuild`, and `rollback` commands. Query requests carry a `repositoryId` and exact `revision`; a request for a previous revision returns `REVISION_OUTDATED` with the current revision.

## Schema and UAT controls

The persisted index contract is schema version 2. It has no distributed-ownership state or compatibility decoder. A database created by an older schema must be rebuilt through the schema-maintenance and repository-rebuild flow before the new Query is deployed.

The `uat` Spring profile adds an in-memory pre-publication gate and repository-scoped `RESET` job endpoints. They use the normal dispatcher and admin token, require the `semantic_uat` database, and are absent outside the UAT profile. Production publication is immediate.
