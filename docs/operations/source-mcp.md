# Source MCP: deployment and operation

This release is source-first, not the old semantic-review service. Indexer is the **only writer** and holds approved Git URLs/credentials, private durable jobs and staging. Query has a distinct read token and accesses only immutable published source; it never calls Indexer, fetches Git, prepares commits or executes repository code. Both are separate Java 21 processes; use a local POSIX filesystem, not NFS or independent distributed mounts. No MongoDB, JDT LS, parser, inference server or target repository build is required.

## Bootstrap and trust boundaries

Provision a **new** service-owned host parent for one Indexer `/data` bind mount. Bootstrap ownership before first startup: parent and private admin directories owned by writer UID:GID `10001:10001`, private mode `0700`; `/data/source-published` owned by writer, mode `0755`, published directories readable/traversable and files `0644` before atomic publication. The Query container runs UID:GID `10002:10002` and receives **only** the published child at `/data/source-published:ro`. Bind mounting the full parent into Query, or staging and published as separate volumes, defeats the isolation/same-filesystem atomic rename prerequisite. Protect the published parent against untrusted replacement; do not assume a read-only application flag can substitute for the mount and UID boundary. On the host keep `/data` private; use an administrator-controlled bootstrap for a new empty mount, not a recursive chown/delete of pre-existing data. The [image smoke](testing.md#verification-commands) exercises the actual UID/mount/read-only boundary on a fresh disposable root.

Run Indexer and Query with **separate secret-store injections** of `SEMANTIC_INDEXER_ADMIN_TOKEN` and `SEMANTIC_QUERY_API_TOKEN`; do not put real tokens in files committed to Git, URLs, logs or MCP output. Git `GIT_USERNAME`/`GIT_TOKEN` (when required) and optional approved read-only local Git mount belong to Indexer only. Restrict Indexer `/index` and `/mcp` to private administrator ingress with validated TLS and appropriate network access controls; expose Query `/api/v1` and `/mcp` through authorized TLS ingress. Localhost HTTP smoke does not establish remote TLS or ingress safety. Do not put an Indexer URL or its environment/mounts in Query.

Indexer config uses `semantic.source-admin-root` (`SEMANTIC_SOURCE_ADMIN_ROOT`, default `/data/source-admin`) and `semantic.source-published-root` (`SEMANTIC_SOURCE_PUBLISHED_ROOT`, default `/data/source-published`). For each approved ID configure `semantic.repositories.<id>.url`, `.default-branch`, `.display-name`, and optional `.project-guide-path` on **Indexer only**. Query config `semantic.query.source.published-root` (actual YAML environment placeholder `SEMANTIC_SOURCE_PUBLISHED_ROOT`), `rg-executable` (`SEMANTIC_QUERY_RG_EXECUTABLE`, default `/usr/bin/rg`) and explicit `allowed-repositories` list. Query also supports `read-content-bytes`, `search-timeout`, `read-timeout`, `list-timeout`, `max-active-searches`; shipped values are 65,536 bytes, 5s, 2s, 2s and 2. Empty allowlist denies all repositories, regardless of registered IDs. Remove old repository/package/class/method restriction settings, including structured list entries, only after explicitly approving a new repository/source scope; retained nonempty obsolete restrictions reject Query during context initialization, before the web server binds, even if the same repository is explicitly allowed under the new source policy. Query has no private Git URL/token.

Indexer publishes a sanitized `repositories.json`; registration is **not** preparation. The private bare repository/jobs/staging live under `source-admin`; published `repositories.json`, `<id>/state.json`, and `<id>/revisions/<sha>/{manifest.json,inventory.jsonl,tree/}` live under `source-published`. The manifest and policy formats are version **1**. Query admits only an allowlisted ID and a revision in atomic state membership whose receipt/manifest digest and versions match. A directory on disk alone, including an orphan after interruption, grants no read. Staging and final revision directory are on the same filesystem: Indexer flushes the complete tree/metadata, atomically renames the revision, then atomically replaces state containing membership and current together. No copy fallback or in-place overwrite of an existing published SHA.

## Repository origin and storage identity

Each repository ID is durably bound to the exact approved Git endpoint string.
Indexer stores an opaque SHA-256 fingerprint in
`source-admin/repositories/<id>/origin.sha256` and
`source-published/<id>/origin.sha256`; raw URLs and Git credentials are not
published. Keep the configured URL spelling stable: scheme, host, port, SSH user,
path and spelling differences distinguish origins. Embedded URL passwords,
HTTP(S) user-info, queries and fragments are unsupported; HTTP authorities are
validated strictly, and rejected parser input is never retained in exception
causes. SSH/scp usernames remain part of the origin identity. Provide
authentication through the separate Git credential settings and never embed
tokens in URLs.

Startup validates both namespaces before rewriting the registry or dispatching
work. Admission, cached-object/already-published reuse, worker execution and
recovery also check the binding. A URL change under the same ID is not a rename:
use a new repository ID with fresh per-ID namespaces, or explicitly fresh storage.
Replacing just the admin or published root cannot rebind the other namespace.
Do not edit binding files or infer an origin from leftover Git objects.

Private durable jobs now use **format version 2** and retain the origin accepted
with the request through every transition. Public manifest/state/descriptor and
HTTP/MCP result formats remain **version 1**, with unchanged result fields.
There is no private-job-v1 decoder or automatic adoption of populated unbound
namespaces. For pre-release unbound source storage, preserve the old roots for a
matching rollback, bootstrap new private/published roots, and explicitly prepare
the required revisions again. Do not run an old Indexer against the new private
job format or mix old unbound data into the new namespaces.

## MCP session lifecycle

Query `/mcp` uses stateful Streamable HTTP. Each independent client initializes its
own session, retains the server-issued `Mcp-Session-Id`, sends
`notifications/initialized`, and includes that session ID and the negotiated
`MCP-Protocol-Version` on subsequent requests and notifications. Continue sending
`X-Api-Token` on every request; a session ID is not a substitute for authorization.
Use a Streamable HTTP MCP client that manages this lifecycle rather than issuing
uninitialized `tools/call` POSTs.

JSON-RPC request IDs are scoped to a session: independent clients may reuse the
same ID without rejecting or cancelling each other's calls. Send
`notifications/cancelled` with the owning session and request ID to stop its work.
Close an unused session with `DELETE /mcp` carrying the same headers. DELETE and
graceful shutdown interrupt owned work and await terminal cleanup, with a
six-second lifecycle deadline. DELETE does not report success if cleanup exceeds
that deadline. Cancellation preserves the structured tool-error response rather
than leaving its interrupt flag set during HTTP response writes.

After Query restarts, initialize a new session; an already-published exact source
context remains valid and does not require new preparation. Indexer retains its
separate stateless preparation endpoint and admin token.

The pinned MCP provider retains a session until client `DELETE` or server
shutdown; it has no supported idle-eviction setting. Clients must close unused
sessions. Abandoned sessions consume memory until shutdown, so the two-search
limit is not a bound on idle session count or a capacity guarantee.

## Prepare, discover, navigate

Use the private Indexer `/mcp` (`prepare_source`, `get_job`) or the equivalent HTTP calls below, with `X-Api-Token: <admin-secret>`. Save a **canonical lowercase UUID** in durable client storage before the POST. Example bodies omit any secret:

```http
POST /index/repositories/video/source
Content-Type: application/json

{"requestId":"dcc061e8-d89f-46e0-ae76-d336c351d738"}

GET /index/repositories/video/jobs?requestId=dcc061e8-d89f-46e0-ae76-d336c351d738
```

That UUID and repository ID are from the **disposable local source journey**, not production identities. Optional `revision` in the POST is exactly a 40-character lowercase SHA-1 commit already obtainable in the approved repository, not a branch/tag/path/ref; omission fetches the configured default branch and pins the resolved SHA before export. No URL, checkout path or arbitrary branch comes from the caller. HTTP `202` follows durable acceptance; `ACCEPTED` may not have a resolved SHA yet. Capture returned `jobId`; GET `get_job` with **exactly one** `jobId` or `requestId`, scoped to the same repository, and wait for `COMPLETE`. An original request reused, even with the same payload, is not a second submission (`REQUEST_ID_REUSED`); an active repo job is rejected separately. If the POST times out or its result is lost, retain and look up the **original** requestId. A missing lookup leaves acceptance unknown: continue original lookup or stop, never automatically resubmit with a different UUID. After inspecting an explicit terminal failure, a newly authorized attempt gets a **new** UUID. One Indexer process, one dispatcher lane, at most one active job per repository; do not run a second writer.

Read token users connect to Query `/mcp` or five HTTP endpoints; all require `X-Api-Token: <read-secret>`:

```http
GET /api/v1/repositories
POST /api/v1/context                 {"repositoryId":"video"}
POST /api/v1/context                 {"repositoryId":"video","revision":"d366278ff697b2ac7d0510830a840a475ec31794"}
POST /api/v1/files                   {"context":{"repositoryId":"video","revision":"d366278ff697b2ac7d0510830a840a475ec31794"},"directory":""}
POST /api/v1/search-text             {"context":{"repositoryId":"video","revision":"d366278ff697b2ac7d0510830a840a475ec31794"},"query":"upload"}
POST /api/v1/source                  {"context":{"repositoryId":"video","revision":"d366278ff697b2ac7d0510830a840a475ec31794"},"path":"src/main/java/com/example/video/VideoController.java"}
```

The example SHA is **A from a disposable observed fixture**; replace it with the `context` actually returned by your own READY `get_context`. First list may show `NOT_PREPARED`, `PREPARING` or `FAILED` with no revision; ready repositories have `sourceStatus: READY`, `semanticStatus: NOT_READY`. `get_context` only discovers existing publication: top-level ID, optional exact revision; no fetch, implicit preparation or latest-substitution on an exact request. Nested `context` for each navigation call contains only repository ID and revision. Copy A unchanged for all A reads after B becomes current. Configured branch is metadata, not proof an historical SHA still heads that branch.

`list_repositories` and direct-child `list_files` default to 20 items, maximum 100; inventory sorting and opaque pagination are bound to repo/revision/directory/filter. `list_files` depth is one, not recursive. Read defaults to 200 lines, maximum 500 and 65,536 UTF-8 content bytes; entire serialized response is bounded to 512 KiB. Follow `nextCursor` with the same original read window and context, respecting `hasMore`, `startsMidLine` and `endsMidLine` to reconstruct oversized lines without inventing content; start beyond EOF returns no source range. Cursors are **positions**, never permission or a new context. `search_text` is single-line literal case-sensitive (max 256 Unicode code points), with optional narrowing directory/filePattern; no regex or search continuation cursor. If `truncated=true`/`scanComplete=false`, narrow the query/path; do not claim absence from incomplete results. At most two concurrent searches and a 5s search deadline; list/read deadline 2s. Timeout or overflow is a controlled error, not an empty completed search. Lines are one-based; search columns are one-based UTF-16 code units.

`filePattern` matches repository-relative paths; `directory` narrows that same scope and does not rebase the glob. For example, directory `src` with pattern `src/*.java` searches Java files directly under `src`. A read beyond EOF returns empty content with absent `endLine`; an empty response's `startLine` alone is not a citable source range.

Search uses ripgrep's path-sorted directory traversal so the bounded result window and HTTP/MCP order do not depend on worker scheduling. Each search checks complete inventory digest/order once using one read-only file handle; bounded positional lookups on that verified handle validate candidate windows without retaining a whole-repository index. Each unique authorized file is hashed once, and untracked physical hits cannot consume the authorized result limit or cause a false complete scan. Directory traversal order is not global lexicographic file-path order (for example, `a/z.java` can precede `a.java` in rg output); preserve the actual rg prefix. The two-search concurrency limit and original 5s wall deadline still apply; there is no heap sort or search continuation.

The search permit is acquired before revision admission and inventory I/O, remains
held through subprocess cleanup, and is shared by HTTP and MCP. Excess requests
receive `SOURCE_BUSY` without entering admission. Repository listing parses its
descriptors and computes its cursor binding from the same registry byte snapshot;
if the registry changes between pages, obtain a new listing rather than reusing
the rejected cursor.

Only tracked regular supported text at the pinned commit is readable. The same policy excludes `.git`, `target`, `build`, `.gradle`, `node_modules`, `generated` at any depth and `*.class`; direct read cannot bypass it. Symlinks are not followed, submodules are not initialized, binary/unsupported encoding/path/LFS pointers are explicitly unsupported; no filters, hooks, LFS hydration or build scripts execute. A guide optionally committed at configured `project-guide-path` is `AVAILABLE`, `MISSING`, `INVALID` or `DISABLED`, always `freshness: NOT_VERIFIED` if represented. It is a navigation hint, not verified business truth, and its absence never blocks source readiness. See the [external authoring prompt](repository-context-prompt.md).

Query errors are safe and transport-equivalent: `INVALID_ARGUMENT` (400), `REPOSITORY_NOT_FOUND` (404, including invisible repos), `SOURCE_NOT_PREPARED` (409), `REVISION_NOT_PREPARED` (404), `SOURCE_NOT_FOUND` (404), `SOURCE_UNSUPPORTED` (422), `SOURCE_BUSY` (503), `SOURCE_UNAVAILABLE` (503), `SOURCE_TIMEOUT` (504). MCP returns the corresponding error code/message with `isError=true`; internal paths, credentials and raw process errors must not enter client output.

## Release and recovery

A fully published receipt plus state `current.publicationJobId` can reconcile a previously RUNNING job to `COMPLETE` on Indexer restart. Otherwise an interrupted RUNNING job becomes `WORKER_INTERRUPTED`: no automatic fetch, replay, orphan adoption or half-tree publication. Private durable terminal jobs are the status-publication outbox: a failed public preparation-state write is retried by the dispatcher and the latest per-repository terminal status is reconciled on restart, without replacing a newer preparation status. The latest private job is determined by an atomically reserved monotonic per-repository admission sequence and private request-linked job envelope, not wall-clock `acceptedAt`; a reservation without a committed job leaves a harmless gap, while missing/corrupt private ordering refuses recovery rather than guessing. This is a new private admin-root format: do not point this release at prior unwrapped private job records or expect a legacy decoder; use the new service-owned namespace, not an old data migration. Published source format and immutable revision receipts remain version 1. `get_job` by the original request ID is authoritative while public status has not converged; do not submit a new request to repair a stale public status. A failed or disk-full B leaves READY A readable; existing published revisions and their first-publication receipts remain, and Phase 1 has **no GC**. A completed B does not invalidate exact A context. Readership remains independent of Indexer/remote availability after publication.
