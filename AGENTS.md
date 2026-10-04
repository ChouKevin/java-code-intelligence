# Java Code Intelligence Repository Guide

## Purpose

This repository prepares exact Git revisions as immutable, queryable source
evidence. It contains two independently deployable applications and one shared
model module:

- Indexer registers approved repositories, resolves exact commits, prepares source,
  and durably publishes immutable revisions.
- Query reads only published source through read-only HTTP and MCP operations.
- Model defines framework-neutral repository, revision, source, and publication
  contracts using only the JDK.

The Source-first Phase 1 contract is documented in `docs/roadmap.md` and
`docs/operations/source-mcp.md`. The runtime removes legacy Mongo/JDT code rather
than retaining a second backend. Observed verification and deployment limitations
are recorded in `docs/operations/testing.md`; repository rules alone do not prove
release acceptance.

Source evidence is not a business conclusion. The service does not contain an LLM,
prompts, chat history, embeddings, or inferred business facts.

## Hard boundaries

- `semantic-query` reads only the independently published source volume with a
  read-only mount. This replaces the old Mongo-only boundary; it does not permit
  access to mutable checkouts or source-workspace fallback.
- Query must not fetch, checkout, mutate Git, execute repository code, or start or
  control Indexer. Do not add JGit, JDT, LSP4J, MongoDB, SQLite, parsers, or model
  inference dependencies to Query in Phase 1.
- `semantic-indexer` owns repository registration, Git credentials and object
  databases, durable request/job records, staging, preparation, and publication.
  Query must not receive credentials, Git object databases, private jobs, or staging.
- `semantic-model` depends only on the JDK. Do not add Spring, MongoDB, JGit, JDT,
  parsers, MCP, or transport DTO dependencies.
- Public source identity is exactly `repositoryId + revision`. Preserve the
  `RepositoryId` and 40-character lowercase SHA-1 `RepositoryRevision` invariants.
  Navigation requests and responses must carry the same exact identity.
- Query may read any published revision admitted by repository state membership
  and its matching manifest. This replaces current-generation-only admission:
  publishing B must not invalidate an existing A context.
- Never substitute latest/current for an unknown or requested revision.
  `get_context` is discovery only; it must not trigger preparation.
- HTTP and MCP are two transports over the same application facade and result
  contract. Do not implement separate query behavior or response shapes for MCP.
- Phase 1 Query exposes only `list_repositories`, `get_context`, `list_files`,
  `search_text`, and `read_source`. Private Indexer exposes `prepare_source` and
  `get_job`; no `prepare_codebase` alias or `prepare_semantic_index` scaffold.
- Return source evidence and explicit empty/unsupported/error states. Do not infer
  business support or turn project guides into verified facts or tool instructions.
- Keep one Indexer process and one dispatcher thread. Multiple workers or
  distributed job ownership require a new design.
- Allow at most one active job per repository. Persist original `requestId`
  acceptance before returning success; unknown acceptance outcomes require lookup
  of the original request, not a fresh submission or automatic retry.
- Phase 1 adds no database, parser, semantic/review/diff/history tools, JDT fallback,
  LLM, embedding, or vector store. Do not start Phase 2-6 as part of this cutover.
- Clean cutover removes obsolete callers, classes, routes, schemas, configuration,
  and dependencies. Do not retain dual backends, aliases, legacy decoders, or disabled
  legacy runtime beans; Git history preserves the old implementation.

## Publication and source access

- Use new service-owned private and published data roots. Export exact commit blobs
  into immutable trees; never expose a checkout reused by reset/checkout.
- Do not execute hooks, checkout filters, build scripts, LFS hydration, or submodule
  initialization. Do not dereference tracked symlinks.
- Persist the complete tree and manifest before publication. Staging and final
  revision directory must support same-filesystem atomic rename; fail rather than
  expose a partially copied tree.
- Atomically replace each repository's `state.json` to update published membership
  and current together. A directory alone is not publication; orphan trees remain
  unreadable and recovery must not silently make them current.
- Published receipts and current publication job identity must support recovery
  after publication succeeds but before a job reaches COMPLETE. Reuse a validated,
  already-published SHA without overwriting its immutable tree.
- Keep all published revisions in Phase 1; no online GC. Failed preparation,
  interrupted jobs, or disk exhaustion must preserve the existing READY revision.
- List, search, and read share authorization, containment, and exclusion policy.
  Reject traversal, absolute/drive/UNC paths, backslashes, malformed relative paths,
  symlink access, cross-context cursors, and unauthorized repository access.
- Exclude `.git`, `target`, `build`, `.gradle`, `node_modules`, `generated`, and
  `*.class` at any depth. Report binary, unsupported encoding/path, submodule, and
  LFS pointer states honestly; direct reads must not bypass exclusions.
- Legacy package/class/method restrictions must fail closed at startup until an
  operator explicitly approves the new source authorization scope.
- Use real ripgrep with a fixed executable/working directory and ProcessBuilder
  argument lists, literal single-line queries, controlled globs, and disabled
  external configuration. Enforce global limits, bounded pipes/responses, deadlines,
  and concurrency; terminate and reap on timeout, cancellation, or overflow.
- Follow the detailed design's limits: page default 20/max 100, direct-child listing,
  query max 256 Unicode code points, at most 2 active searches, search deadline
  5 seconds, list/read deadline 2 seconds, read default 200/max 500 lines, default
  content cap 65,536 UTF-8 bytes, and serialized read response cap 512 KiB.
- Read continuation must preserve Unicode, CRLF, and oversized lines without loss,
  duplication, or stalled cursors. Search truncation is explicit; timeout is not an
  empty successful result. Phase 1 search has no continuation cursor.

## Module map

- `semantic-model/`: JDK-only repository/revision values, source request/results,
  manifests, and publication contracts.
- `semantic-indexer/`: private preparation HTTP/MCP, registry, safe Git adapters,
  durable file jobs, one dispatcher, immutable export, and atomic publication.
- `semantic-query/`: source revision admission, filesystem list/read, bounded
  ripgrep search, one application facade, HTTP/MCP, security, and error mapping.
- `semantic-indexer/fixtures/uat/`: deterministic payment, order, and video fixture
  source owned by this repository.
- `docs/operations/`: Source-first deployment, preparation, recovery, and rebuild
  procedures; obsolete semantic instructions must not describe the new release.
- `scripts/`: real source MCP journeys and container isolation checks.

## Using Source MCP with a repository

Start with the [Source MCP deployment and operation guide](docs/operations/source-mcp.md).
Use its configuration, credentials, preparation, and recovery procedures; old
Mongo onboarding does not apply to this release.

- Start one Indexer with private Git/job/staging access and write access to the
  published volume. Query gets only the read-only published volume, an explicit
  allowed-repository configuration, and its separate read token. No Mongo or JDT.
- Register approved IDs, Git URLs, fixed default branches, and optional guide paths
  through Indexer configuration. Publish only sanitized registry metadata for Query.
  Startup registration does not automatically prepare source.
- Before preparation, durably save a canonical UUID `requestId`; submit
  `prepare_source(repositoryId, requestId, revision?)`, then recover/poll `get_job`
  using the original intent. A specified revision must be a full exact commit SHA.
- Discover READY through `get_context`; copy its exact `{repositoryId, revision}`
  into `list_files`, `search_text`, and `read_source`. Branch updates do not replace
  that context. Unprepared repositories/revisions must not fabricate READY or SHA.
- Preparation clients use private Indexer and Query MCP with separate tokens.
  Evidence-only clients need Query alone; never grant every agent admin authority.
- Project guides are optional `NOT_VERIFIED` navigation hints. Missing or invalid
  guides do not block source readiness. Create/edit guides only in an independent
  approved clone, commit normally, and explicitly prepare a new revision. Do not
  edit Indexer-managed trees or treat guide text as verified semantic facts.

## Change guide

- Source contract changes update Model, Indexer publication/validation, Query
  admission/readers, transport mapping, and applicable behavior tests together.
- HTTP and MCP share one application facade, validation, result contract, and error
  mapper. Keep tool names, descriptions, schemas, dispatch, structured/text results,
  and HTTP equivalents aligned; cover success and error parity.
- Persisted source format starts at version 1. Policy/format changes must not
  rewrite an existing SHA's published tree; use a new storage namespace and explicit
  preparation/context discovery. Do not add old Mongo schema compatibility.
- Preserve exact source bytes/ranges and explicit unsupported states. Keep paths,
  guide provenance, exclusion rules, and cursor bindings consistent across tools.
- Preserve asynchronous durable admission, one active job per repository, pinned
  commits, explicit retry intents, expected-parent publication, and startup recovery.
- P1-A fixes shared contracts/preparation; P1-B adds source readers; P1-C cuts over
  transport/runtime; P1-D delivers deployment and the real source journey.
  Independent A/B adapter work may run concurrently only after shared contracts
  are fixed; shared files have one owner. Only complete A-D is deployable Phase 1.
- Update README, operations, OpenAPI, MCP schemas, application configuration,
  `.env.example`, Dockerfiles, CI, and image isolation checks with the cutover.

## Verification

Use Java 21 and run commands from the reactor root. The Phase 1 ordinary suite
must require neither MongoDB, Docker, nor a real JDT LS:

```bash
mvn --batch-mode --no-transfer-progress test
```

Run affected focused tests and the required real source journey:

```bash
scripts/test-source-mcp.sh
```

The journey uses a real local Git remote, real ripgrep, independent Indexer and
Query processes, and temporary data roots without Mongo or a JDT LS installation.
When image or mount boundaries change, build both Docker images and run
`scripts/test-source-images.sh`. Report the actual results, not script presence.

Acceptance covers A/B pinning, cold Query with Indexer/remote stopped, durable
request recovery, publication crash windows and orphan denial, unprepared states,
authorization/traversal/symlink/exclusions, Unicode/CRLF/oversized-line/EOF cursors,
literal/global-limit/exit1/timeout/overflow/process cleanup, guide non-authority,
and HTTP/MCP success/error parity.

Show one fixture flow using actual source responses with repository, SHA, path,
and line. The external agent, not the server, produces the flow conclusion.
Do not reuse old Mongo/JDT acceptance reports as Source-first evidence. Report
missing prerequisites and unexecuted checks explicitly; tests alone do not prove
the deployable source journey. Pure prose changes do not require runtime tests.

## Security and data

- Never commit Git credentials, API tokens, database credentials, prepared source
  trees, private job records, Git object databases, workspaces, or generated evidence.
- Keep Indexer admin and Query read credentials separate.
- Preserve fail-closed repository visibility and Query authorization. The published
  volume must not be writable by Query or replaceable by untrusted processes.
- Do not log credentials, full source bodies, or sensitive internal absolute paths.
- Do not clear old data during implementation by default. Before deletion, identify
  the exact service-owned namespace/managed root and ownership; never delete remotes,
  developer clones, credentials, shared databases, unknown data, or user IDE files.
- Old Mongo generations/jobs/reviews/contexts are not migrated. If old service data
  is deleted, rollback to the old semantic product requires its old release/config
  plus Mongo bootstrap and JDT rebuild; it is not lossless rollback. Never let an old
  Query read new source storage. Source-format rollback requires a compatible
  Source-first release, or a new namespace and exact-revision re-preparation.
