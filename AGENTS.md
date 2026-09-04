# Java Code Intelligence Repository Guide

## Purpose

This repository turns exact Java repository revisions into immutable, queryable
code facts. It contains two independently deployable applications and one shared
model module:

- Indexer checks out Git, runs JDT LS and syntax extraction, and publishes sealed
  MongoDB generations.
- Query reads the current sealed generation and exposes read-only HTTP and MCP
  operations.
- Model defines the framework-neutral identities, facts, relations, repository
  values, and persisted index contract shared by both applications.

Semantic reports code evidence. It does not contain a model, prompts, chat history,
or business conclusions.

## Hard boundaries

- `semantic-query` must remain Mongo-only. Do not add Git checkout, JGit, JDT, JDT
  LS, source-workspace fallback, or model dependencies to Query.
- `semantic-indexer` owns all repository mutation, JDT LS work, extraction,
  validation, and generation publication. Query never starts or controls Indexer.
- `semantic-model` stays framework-neutral. Do not depend on Spring, MongoDB, JGit,
  JDT, MCP, or transport DTOs from this module.
- Query serves only the current published revision. Repository-scoped requests must
  carry `repositoryId` and the exact `revision`; do not silently replace either.
- HTTP and MCP are two transports over the same application facade and result
  contract. Do not implement separate query behavior or response shapes for MCP.
- MCP tools return indexed facts and source evidence. They must not infer whether a
  business feature is supported or fabricate facts for empty results.
- Keep one Indexer process and one dispatcher thread. Multiple workers or
  distributed job ownership require a new design.
- Do not add compatibility decoders for removed pre-release index schemas. Follow
  the documented rebuild and release order when persisted data changes.

## Module map

- `semantic-model/`: code facts, repository values, query values, projection names,
  Mongo collection contracts, and schema versions.
- `semantic-indexer/`: admin API, job dispatcher, Git checkout, incremental planning,
  JDT/JDT-LS adapters, projection writers, validation, and UAT fixtures.
- `semantic-query/`: current-generation selection, Mongo readers, application facade,
  HTTP controllers, MCP catalog, security, and result mapping.
- `semantic-indexer/fixtures/uat/`: deterministic payment, order, and video fixture
  source owned by this repository.
- `docs/operations/`: schema evolution, indexing, deployment, and recovery rules.
- `scripts/`: image smoke tests and deployed Indexer/Query contract checks.

## Change guide

- New or changed code fact: update `semantic-model`, the Indexer projection, its
  validation, Query decoding/mapping, and focused tests together.
- New Query operation: add one application contract and facade path, then expose the
  same behavior through HTTP and MCP with parity coverage.
- New MCP tool: keep its name, description, input schema, facade dispatch, structured
  result, and HTTP equivalent aligned. Tool errors must use the shared error mapper.
- Persisted projection change: follow `docs/operations/tool-data-evolution.md`; update
  the schema/projection version and verify the required rebuild and release order.
- Extraction change: preserve exact source ranges, stable fact identities, and honest
  handling of unresolved or unsupported source.
- Repository lifecycle change: preserve asynchronous jobs, exact commits, one active
  job per repository, explicit retries, and expected-parent publication.

## Verification

Use Java 21 and run commands from the reactor root. The ordinary suite requires
neither Docker nor a real JDT LS:

```bash
mvn --batch-mode --no-transfer-progress test
```

Run Mongo integration tests for persisted schema or Query storage changes:

```bash
mvn --batch-mode --no-transfer-progress -Pmongo-it verify
```

Run real JDT LS and deployed checks only when their boundaries change:

```bash
JDTLS_HOME=/opt/jdtls mvn --batch-mode --no-transfer-progress -Pjdtls-it test
JDTLS_HOME=/opt/jdtls scripts/test-indexer-query-contract.sh
```

Also run the relevant fixture Maven tests when extraction behavior changes. Do not
turn real JDT LS, Docker, or deployed tests into requirements for the ordinary unit
suite.

## Security and data

- Never commit Git credentials, API tokens, MongoDB credentials, indexed repository
  contents, JDT LS workspaces, or generated evidence.
- Keep Indexer admin and Query read credentials separate.
- Preserve fail-closed repository visibility and Query authorization.
- Do not log credentials, full source bodies, or sensitive local paths.
