# Java Code Intelligence Delivery and Agent Cutover Handoff

## Purpose

This handoff is the execution entry point for the work that follows the history-preserving
repository extraction. It covers the independent delivery baseline, preparation for an external
deployment, the Java System Agent consumer cutover, and final removal of the embedded service
source.

Do not create a second service implementation, shared Java library, or compatibility layer. HTTP
and MCP adapters may project the same application services, but each transport keeps its own
adapter contract.

## Current state

### Java Code Intelligence

- Local repository: `/home/shuu/java-code-intelligence`
- Remote: `git@github.com:ChouKevin/java-code-intelligence.git`
- Default branch: `main`
- Integration branch: `uat`
- Extracted baseline on both branches: `71952639132f66b89e862b3f900ea6719f5244c2`
- The service is a standalone Java 21, Spring Boot 4.1, Spring AI 2.0 Maven project
- `mvn clean test` passed after extraction
- There is currently no GitHub Actions workflow, Dockerfile, image publication, or deployment
  manifest

### Java System Agent

- Local repository: `/home/shuu/java-system-agent`
- Remote: `git@github.com:ChouKevin/java-system-agent.git`
- Integration branch: `uat`
- Extraction-document baseline: `1f6194e9`
- The embedded `java-semantic-service/` tree remains intentionally present until the external
  consumer cutover passes
- `java-semantic-service/docs/uat/` is local, untracked UAT material. Never commit or delete it
  during automated cleanup

### Known non-blocking warnings

- `JdtLsSemanticExceptionNormalizer` references `ThreadDeath`, which is deprecated and marked for
  removal
- A small number of production and test APIs emit deprecation warnings

These warnings are not part of the delivery-baseline task unless they become build failures.

## Locked decisions

1. Ordinary CI runs for pull requests and pushes to `uat` and `main`
2. Ordinary CI executes `mvn clean test` without launching a real JDT LS process
3. Real JDT LS integration runs through `workflow_dispatch` and a weekly schedule
4. A Dockerfile is included and built by ordinary CI
5. CI does not authenticate to a registry and does not push an image in this milestone
6. The Docker image contains one explicitly pinned JDT LS distribution under `/opt/jdtls`
7. The JDT LS download is verified by SHA-256 and must not use an unpinned `latest` URL
8. Docker and the JDT LS integration workflow consume one version/checksum source of truth
9. Runtime repository clones and JDT LS workspace data remain outside the image under
   `/data/repos` and `/data/jdtls`
10. The image runs as a non-root user and contains no API token, Git credential, repository source,
    or generated JDT LS workspace
11. The existing HTTP, OpenAPI, MCP, monitoring, authorization, revision, range, and typed-identity
    contracts do not change
12. No Agent consumer change or embedded-source deletion occurs until an independently reachable
    service instance exists
13. There is no compatibility period after the cutover. The external repository becomes the only
    service source

## Delivery sequence

Each phase has a hard completion gate. Do not start a later phase merely because the earlier code
has been written.

## Phase 1: Independent CI and Docker build

Work in `/home/shuu/java-code-intelligence` on a feature branch. Keep commits behavior-focused and
use the repository's Conventional Commit style.

### 1. Correct repository-state documentation

Update repository-owned documentation so it no longer describes the independent repository as a
temporary extraction source:

- `AGENTS.md`
- `README.md`
- `docs/roadmap.md`

Mark extraction as complete. Keep R1 as the active delivery boundary. Document that image
publication and deployment remain out of scope for this phase.

### 2. Establish one pinned JDT LS installation contract

Add one repository-owned source of truth for:

- JDT LS release identity
- Distribution URL or deterministic URL inputs
- SHA-256 checksum

Add one small installation script shared by Docker and GitHub Actions. It must:

- fail on download, checksum, archive, or destination errors
- install only into an explicitly supplied destination
- leave no downloaded archive behind
- avoid logging credentials or environment contents
- reject an empty version, URL, checksum, or destination

Do not add a second Java-side installer. Runtime Java code continues to consume `JDTLS_HOME` and
does not own downloads.

### 3. Add the Docker build

Add a multi-stage `Dockerfile` and `.dockerignore`:

- builder: Maven plus Java 21, producing the Spring Boot executable JAR
- runtime: Java 21 JRE, the application JAR, and the pinned JDT LS installation
- runtime user: non-root
- JDT LS home: `/opt/jdtls`
- repository data: `/data/repos`
- JDT LS workspace data: `/data/jdtls`
- application start: the executable JAR only; no shell-based bootstrap or runtime download

The image must not copy `.git`, `target`, local environment files, credentials, runtime repository
clones, JDT LS workspace data, or local UAT material.

Do not add Docker Compose, Kubernetes resources, registry login, image tags intended for release,
or deployment-specific secrets.

### 4. Add ordinary CI

Add `.github/workflows/ci.yml` with:

- `pull_request`
- pushes to `uat` and `main`
- Java 21 setup
- Maven dependency caching
- `mvn clean test`
- Docker build after Maven verification succeeds
- minimum required GitHub token permissions
- concurrency cancellation for superseded runs on the same ref

The existing tests are the authority for the versioned OpenAPI contract and exact 17-tool MCP
catalog. Do not duplicate those contracts as shell-maintained lists in the workflow.

### 5. Add real JDT LS integration CI

Add `.github/workflows/jdtls-integration.yml` with:

- `workflow_dispatch`
- one weekly schedule
- Java 21 setup
- Maven dependency caching
- installation through the shared pinned JDT LS installer
- `JDTLS_HOME` pointing at that installation
- `mvn -Pjdtls-it test`
- an explicit timeout
- minimum required GitHub token permissions

This workflow is intentionally separate from the ordinary PR path. A lifecycle or semantic
resolution PR must trigger it manually before merge when the change materially depends on real
JDT LS behavior.

### Phase 1 verification

Run locally from `/home/shuu/java-code-intelligence`:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn clean test
docker build -t java-code-intelligence:local .
```

When a valid JDT LS installation is available, also run:

```bash
JDTLS_HOME=/home/shuu/.local/share/jdtls \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  mvn -Pjdtls-it test
```

Phase 1 is complete only when ordinary CI and Docker build pass on the feature branch, the real
JDT LS workflow has one successful manual run, and the changes have passed through `uat` before
`main`.

## Phase 2: Deployment ownership specification

Do not infer a production platform. Before adding deployment resources, record a separate design
decision covering:

- runtime host and network ownership
- service URL and TLS termination
- `SEMANTIC_API_TOKEN` ownership and rotation
- Git credential ownership and supported clone protocols
- persistent ownership of `/data/repos` and `/data/jdtls`
- backup, cleanup, and capacity policy for repository clones and JDT LS workspaces
- process restart and health-check behavior
- resource limits, especially JDT LS heap and active workspace capacity
- image registry, tag immutability, provenance, and promotion from `uat` to `main`
- observability destination and retention

Phase 2 is complete only when an independently reachable UAT instance exists. A Dockerfile or
successful image build alone is not an external deployment.

## Phase 3: Java System Agent consumer cutover

Work in `/home/shuu/java-system-agent` only after Phase 2 provides an independently reachable UAT
endpoint.

### Required behavior

- Keep the Agent dependent only on versioned HTTP contracts and opaque `repoId`
- Preserve revision-pinned requests and fail-closed revision mismatch behavior
- Keep semantic-service implementation classes and build artifacts out of the Agent project
- Preserve the existing five code-intelligence QUERY capabilities:
  - list entry points
  - lookup API route
  - suggest API route
  - outgoing call graph
  - incoming call graph
- Preserve timeout, authorization, transport-failure, and inbox retry semantics
- Keep MCP adoption out of this cutover; it remains a separate Agent-side milestone

Prefer changing deployment configuration when the existing HTTP adapter is already externally
configurable. Do not add an alternate adapter, compatibility URL, fallback to the embedded source,
or shared Java DTO dependency.

### Cutover verification

Verify against the independently deployed UAT service:

1. Repository catalog and revision resolution
2. Each of the five QUERY capabilities through the Agent runtime
3. A revision mismatch response
4. An authorization failure
5. Semantic service unavailability and the existing bounded inbox retry path
6. Agent restart recovery without changing `repoId`, revision, run identity, or session ordering
7. No Agent process loads classes or artifacts from the embedded Maven project

Run the smallest focused Agent tests first, followed by:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -f pom.xml clean test
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -f pom.xml test \
  -Dtest=ApplicationModularityTests
```

Use `-Ppostgres-it verify` only when the cutover changes inbox persistence, retry, recovery, or
delivery behavior.

Phase 3 is complete only after the external UAT flow passes and the Agent `uat` deployment no
longer depends on the embedded service directory.

## Phase 4: Remove the embedded service source

Before deleting anything, move the untracked local directory
`/home/shuu/java-system-agent/java-semantic-service/docs/uat/` to a safe local path outside the
embedded service tree. It must remain untracked and must not be copied into the independent
repository automatically.

Then remove the tracked embedded service in one explicit Agent-repository change:

```bash
git rm -r java-semantic-service
```

Also update Agent-owned references that still describe, build, or navigate to the embedded Maven
project. Retain the extraction and cutover handoff evidence under Agent-owned documentation.

Do not retain:

- a compatibility source tree
- a Git submodule
- a copied service JAR
- a parent Maven module
- a fallback local implementation
- duplicate OpenAPI or MCP contract sources owned by the Agent

The removal is complete only when the Agent ordinary suite and Modulith verification pass, the
Agent deployment still reaches the external service, and a repository search finds no active build
or runtime dependency on `java-semantic-service/`.

## Stop conditions

Stop and ask for an architecture decision rather than guessing when:

- the selected JDT LS distribution cannot be verified by checksum
- the Docker runtime needs credentials at build time
- ordinary tests require a live JDT LS process
- a workflow needs broad write permissions
- deployment requires a public unauthenticated endpoint
- the Agent adapter cannot use an external URL without implementation changes
- cutover changes inbox retry, persistence, recovery, or session ordering
- the external service contract differs from the embedded baseline
- removal would delete or commit local `docs/uat/` material

Any new HTTP or MCP contract, compatibility behavior, persistence model, security policy, or
runtime ownership rule requires a separate approved design before implementation.

## Final evidence to retain

- successful ordinary CI URL and commit
- successful Docker build commit
- successful manual and scheduled JDT LS integration runs
- pinned JDT LS release identity and checksum source
- deployment ownership decision
- independently reachable UAT service identity
- Agent external UAT scenario results
- Agent test commands and outcomes
- embedded-source removal commit
- confirmation that `java-code-intelligence` is the sole service source
