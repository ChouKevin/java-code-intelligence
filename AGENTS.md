# Java Code Intelligence Repository Guide

## Purpose and modules

The service prepares approved Git repositories at exact revisions and exposes
immutable source evidence through HTTP and MCP. It supports source navigation
and protected revision retention. Callers interpret the evidence; optional
project guides are `NOT_VERIFIED` navigation hints, not instructions or facts.

| Module | Responsibility |
| --- | --- |
| `semantic-indexer/` | Own repository registration, Git credentials/objects, durable jobs, staging, immutable export, publication and retention. |
| `semantic-query/` | Serve authorized list/search/read operations using only the read-only published volume and real ripgrep; remain independent of Indexer and private storage. |
| `semantic-model/` | Define repository/revision values and source/publication contracts using only the JDK. |

Query provides `list_repositories`, `get_context`, `list_files`, `search_text`
and `read_source`. Private Indexer provides `prepare_source` and `get_job`.
HTTP and MCP share the application facade, validation, result and error contracts.

## Core contracts

- Carry the exact `repositoryId + revision` identity through navigation requests
  and responses. Preserve repository-ID validation and the 40-character lowercase
  SHA-1 invariant. An unknown or expired revision is an explicit error, not latest.
- Bind each repository ID immutably to its approved origin in private and published
  storage and accepted jobs. Verify the binding through admission, reuse, execution,
  publication and recovery; a changed origin requires a new ID/fresh namespace.
- Use one Indexer process and dispatcher, with at most one active job per repository.
  Accept the canonical UUID `requestId` durably before success; resolve uncertain
  acceptance by looking up that original request rather than automatically retrying.
- Export exact Git blobs into service-owned immutable trees. Persist complete
  content/metadata before same-filesystem atomic publication; atomically update
  repository membership and current together. Membership plus a matching manifest,
  not a directory alone, authorizes reads. Keep publication receipt recovery intact.
- Keep `get_context` discovery-only and Query independent of preparation. Published
  non-current revisions remain readable by exact identity until valid withdrawal.
  Failed or interrupted preparation preserves the existing READY publication.
- Policy 2 retains non-current revisions for at least 30 elapsed days after
  replacement; current is protected. The idle dispatcher checks after startup
  recovery and with a configurable 24h fixed delay. Busy reads defer collection;
  disabling retention stops new and pending physical deletion.
- Hold read guards through source consumption and actual process/worker cleanup.
  Durable, origin-bound deletion intents protect same-SHA republication and limit
  recovery to validated managed paths. Use fresh namespaces for policy 2.
- Public manifest/state/descriptor and HTTP/MCP results remain format 1; private
  preparation jobs remain format 2. Preserve existing published bytes when changing
  policy/format; use a new namespace and explicit preparation instead of rewriting.
- Apply the same authorization, containment and exclusion policy to list, search
  and direct read. Preserve Unicode, CRLF, oversized-line/EOF continuation and
  context-bound cursors. Return explicit unsupported, truncated and error states.
- Use real ripgrep with a fixed executable/working directory, argument lists,
  literal queries and disabled external configuration. Preserve bounded I/O,
  response/concurrency limits and deadlines; terminate and reap on cancellation,
  timeout or overflow. Exact limits and recovery rules are in
  [Source MCP operations](docs/operations/source-mcp.md).

## Working on changes

- Resolve the approved worktree, repository root and revision before editing.
  Read code, configuration and guidance from that same worktree; give workers
  explicit paths. Preserve dirty user worktrees and unrelated files.
- Follow the approved scope in the [roadmap](docs/roadmap.md). Historical plans and
  acceptance logs are evidence, not unfinished task lists. Reload the correct
  context when worktree instructions differ from the session's injected snapshot.
- Update shared contracts, publication/admission, all callers, HTTP/MCP schemas
  and behavior tests together. Give shared files one integration owner and remove
  obsolete paths rather than retaining aliases or compatibility scaffolds.
- Update affected README, operations, OpenAPI, configuration, images and CI with
  the changed contract. Deployment/recovery details belong in the operations guide;
  phase history belongs in the roadmap rather than this quick-reference guide.
- Keep shared guidance and build/CI configuration tracked. Keep private settings
  in `.local/`, plans/specs in `docs/superpowers/`, and execution records in
  `.superpowers/`; these local files are excluded from team commits.

## Verification

Use Java 21 and Maven 3.9.x from the selected reactor root. Follow
[Testing and verification](docs/operations/testing.md) for prerequisites,
commands, lane selection and acceptance evidence.

- During implementation, run focused native tests and necessary changed-path
  smoke checks. Reserve CI and image builds/smoke for the completed feature and
  local review, with publishing authorization. Reuse valid same-snapshot evidence.
- At final acceptance, use `scripts/verification-scope.sh` for the affected
  ordinary/journey/image gates; run images on isolated CI. Diagnose failures,
  fix their cause and verify the corrected snapshot without weakening assertions.
- Prose-only changes need guidance/link checks, not Maven or containers. Fixture
  source is journey input, not a separate business build.
- Admit at most one heavy local verification lane after checking host capacity.
  Read `.local/workstation-safety.md` when present for operator-specific limits;
  insufficient or unknown headroom means defer to an authorized isolated runner.
- Report exercised results and unexecuted checks separately. Agent acceptance
  cites actual repository, SHA, path and line; source-flow conclusions belong to
  the external agent, not the server. See the
  [agent acceptance checklist](docs/operations/mcp-agent-acceptance.md).

## Security and data

- Keep Indexer admin and Query read credentials separate. Query receives only its
  explicit repository allowlist, read token and read-only published mount; private
  Git, jobs, staging and preparation control remain exclusively with Indexer.
- Keep repository execution outside source preparation: no hooks, checkout
  filters, builds, LFS hydration, submodule initialization or symlink dereferencing.
- Preserve fail-closed authorization, safe path access and unsupported-state
  reporting. Protect published roots from untrusted writes or inode replacement.
- Keep credentials, actual repository settings, prepared trees, private records
  and generated evidence out of commits. Keep secrets, full source bodies and
  sensitive internal paths out of logs and client errors.
- Preserve unrelated services, clones, credentials, IDE files and data. Deletion
  requires a verified service-owned scope and authorization; verification never
  authorizes pruning shared storage, stopping unrelated services or changing
  global resource limits.
