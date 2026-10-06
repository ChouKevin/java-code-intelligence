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

## Session and worktree scope

- At session start, resolve the task's working directory, repository root and
  revision once. Use the user-approved task/handoff baseline; do not assume the
  original main checkout is current merely because it is the parent directory.
- Read code, configuration, tests and operations guidance from the same worktree.
  Give workers its absolute root and require explicit paths or a confirmed cwd.
  If files contradict the approved baseline, resolve the workspace mismatch before
  changing code or running an unrelated legacy suite.
- Preserve dirty user worktrees. Do not reset, stash, pull or overwrite them to
  reach a task baseline; use the approved isolated worktree instead.
- Historical plans and acceptance logs are evidence, not outstanding task lists.
  Use current code and operations contracts; do not restart a completed migration.
- Context files load when a session starts. Do not assume editing this file
  replaces an already-injected instruction snapshot. Restart/reload from the
  correct worktree when injected rules conflict with its current instructions.
- Keep local plans/specs in `docs/superpowers/` and agent execution records in
  `.superpowers/`; neither belongs in team commits. Do not ignore shared agent
  guidance such as this file or unrelated tracked editor configuration.
- Follow the current roadmap phase. Do not add dependencies, runtime workers,
  scaffold endpoints or acceptance workloads for future phases; preserve current
  source, authorization, durability and recovery guarantees.

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
- Bind each repository ID immutably to its approved Git origin in both private
  and published storage. Persist that origin in accepted jobs and verify it before
  admission, cached reuse, execution, publication and recovery. A changed origin
  requires a new ID/fresh namespace; never adopt populated unbound storage.
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
- Policy 2 retains non-current revisions for at least 30 elapsed days after
  replacement; current is never reclaimed. The existing idle dispatcher scans
  at startup and with configurable 24h fixed delay; disabling stops pending deletion.
  Shared read guards cover full operations and process cleanup; durable private
  intents fence same-SHA publication and recover only validated managed paths.
  Use fresh namespaces, not policy-1 migration. No quota or extra worker/API.
  Failed preparation, interrupted jobs or disk exhaustion preserve existing READY.
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
- `semantic-indexer/fixtures/uat/`: tracked video source corpus for the real source
  journey, not a prerequisite business build. Order/payment guide cases are generated.
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
- Public manifest/state/descriptor and HTTP/MCP result formats remain version 1;
  private durable jobs use version 2 with the accepted origin binding. Do not
  conflate these versions or add a private-job-v1/old Mongo compatibility decoder.
  Policy/format changes must not rewrite an existing SHA's published tree; use a
  new storage namespace and explicit preparation/context discovery.
- Preserve exact source bytes/ranges and explicit unsupported states. Keep paths,
  guide provenance, exclusion rules, and cursor bindings consistent across tools.
- Preserve asynchronous durable admission, one active job per repository, pinned
  commits, explicit retry intents, expected-parent publication, and startup recovery.
- Fix shared contracts before parallel adapter changes, and give shared files one
  integration owner. Completed Phase 1 implementation steps are not new tasks.
- Update affected README, operations, OpenAPI, MCP schemas, configuration, images
  and CI alongside a changed contract; do not rebuild unrelated surfaces.

## Verification

Use Java 21 and Maven 3.9.x from the selected worktree's reactor root. Follow
`docs/operations/testing.md` for commands. Separate intermediate task checks from
final feature acceptance; a task completion is not a release gate.
Use `bash scripts/verification-scope.sh --base <commit> --head <commit>` for
final lane selection (`--full` for explicit acceptance); unknown/unavailable
diffs select all. CI and local guidance consume the same selector. Non-draft
feature-ready PR events or final dispatch trigger CI; pushes do not. Draft
events may still create skipped workflow runs. The terminal `Feature acceptance`
gate rejects required lane skips/cancellations. Branch protection is separate.

### Intermediate tasks

- Do not run CI or Docker image builds/smoke, locally or remotely. Use local
  checkpoint commits; do not push/open a ready PR that triggers CI mid-feature.
  Inspect current workflow triggers before assuming remote pushes are inert.
- Run only the smallest affected native tests and necessary changed-path smoke.
  Journey/fixture-source changes use the real journey rather than unrelated
  fixture business builds. Prose-only changes need guidance/link checks only.
- Reuse valid same-snapshot evidence. A new session, reviewer or task boundary
  does not require another full reactor, journey or historical acceptance replay.
- Allow at most one resource-heavy local verification lane across all worktrees.
  Never overlap Maven, a real journey, Docker builds or another executor's checks.
  Check host and WSL memory, swap and physical disk headroom before a heavy run;
  if insufficient, defer the run rather than retrying under pressure. Cancel only
  owned work on resource exhaustion, preserving diagnostics and request IDs.
- Keep the user's local PostgreSQL and its Docker engine/data running. Do not
  stop Docker/WSL, prune data/caches, alter global limits or terminate unrelated
  IDE/service processes to make a check pass. PostgreSQL is not a Source-first
  product or test dependency.

### Final feature acceptance

- Complete all feature tasks and local review before triggering CI. Run the
  selected gates on that integrated snapshot; keep pending/failed gates explicit.
- Indexer/Query behavior changes require affected ordinary tests and the real
  source journey. Image, mount, startup or security changes also need image smoke.
- Shared Model/build/CI changes or uncertain scope select full ordinary, journey
  and image gates. Mixed changes use their union. This selection does not require
  running full gates at every intermediate task.
- Run image builds/smoke on an isolated CI runner, not in the default local loop.
  Do not duplicate the same acceptance on branch push and PR or after every task.
  Local image verification requires a separately approved, resource-bounded run.
- Diagnose final-gate failures, fix the cause and verify the corrected snapshot.
  Do not suppress failures, widen timeouts or reuse old results for changed code.
  Final acceptance is required before declaring the feature complete or merging.

The ordinary reactor requires no MongoDB, Docker or real JDT LS:

```bash
mvn --batch-mode --no-transfer-progress test
```

For the selected source boundary, run `scripts/test-source-mcp.sh` with a real
local Git remote, real ripgrep, independent Indexer/Query processes and disposable
roots. Set `SOURCE_TEST_RG` explicitly to an absolute real rg executable when
needed; do not commit a workstation/editor fallback. At final acceptance, the
selected image gate builds both images and runs `scripts/test-source-images.sh`
on CI. Report actual results, not script presence, scheduled or skipped checks.

Full release acceptance covers A/B pinning, cold Query with Indexer/remote stopped,
durable request recovery, publication crash windows and orphan denial, unprepared
states, authorization/traversal/symlink/exclusions, Unicode/CRLF/oversized-line/EOF
cursors, literal/global-limit/exit1/timeout/overflow/process cleanup, guide
non-authority, and HTTP/MCP success/error parity.

For full agent acceptance, show one fixture flow using actual source responses
with repository, SHA, path and line. The external agent, not the server, produces
the flow conclusion.
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
