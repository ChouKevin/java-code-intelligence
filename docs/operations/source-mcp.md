# Source MCP: deployment and operation

This release is source-first with an optional Java syntax index, not the old semantic-review service. Indexer is the **only writer** and holds approved Git URLs/credentials, private source/index jobs and staging. Query has a distinct read token and accesses only immutable published source/index; it never calls Indexer, fetches Git, prepares commits, parses Java or executes repository code. Both are separate Java 21 processes; use a local POSIX filesystem, not NFS or independent distributed mounts. Indexer embeds JavaParser 3.28.2; both services embed SQLite JDBC 3.53.4.0. No external database, MongoDB, inference server or target-repository build is required.

## Local-only configuration and evidence

Keep the actual source repository IDs, Git URLs, branch assignments, guide paths,
Query allowlist and MCP client settings outside version control. This includes
the operator's nine-repository deployment: the public repo ships configuration
keys and synthetic fixtures, not that deployment's inventory.

Prefer configuration outside this checkout. When local files are needed here,
use ignored `.local/indexer.yml` and `.local/query.yml`, with separate environments
and credentials for each service. `.local/` is a Git/Docker-context exclusion,
not an automatic configuration loader; supply these files through the existing
external deployment configuration mechanism. Never mount Indexer configuration
or credentials into Query. Keep real values out of `.env.example`, packaged
`application.yml`, documentation, fixtures, commit messages and PR descriptions.

Git and the Docker context also exclude local environment overrides, private key
files and secrets directories. Existing `data/mcp-repositories/` source clones,
runtime settings and preparation records stay local; do not copy them into tracked
files or delete them as repository housekeeping. IDE metadata, agent/spec-vault
records and generated evidence are not shared source artifacts.

Ignoring an already-tracked file is insufficient: remove it from the Git index
while preserving its local copy. This removes it from subsequent remote trees,
not Git history or existing clones. Exposed credentials require rotation and a
separately approved history-cleanup procedure; ignore rules cannot revoke them.

## Bootstrap and trust boundaries

Provision a **new** service-owned host parent for one Indexer `/data` bind mount. Bootstrap ownership before first startup: parent and private admin directories owned by writer UID:GID `10001:10001`, private mode `0700`; `/data/source-published` and `/data/index-published` owned by writer, mode `0755`, published directories readable/traversable and files `0644` before atomic publication. Query runs UID:GID `10002:10002` and receives **only** these two children read-only, never the full `/data` parent. Keep private staging and both publication roots on the same filesystem for atomic rename; do not split them into independent volumes. Protect published parents against untrusted replacement; read-only application flags cannot substitute for mount/UID isolation. Bootstrap only new empty storage, not recursive ownership changes/deletion of existing data. The [image smoke](testing.md#verification-commands) is a pending executable check of this boundary, not execution evidence.

Run Indexer and Query with **separate secret-store injections** of `SEMANTIC_INDEXER_ADMIN_TOKEN` and `SEMANTIC_QUERY_API_TOKEN`; do not put real tokens in files committed to Git, URLs, logs or MCP output. Git `GIT_USERNAME`/`GIT_TOKEN` (when required) and optional approved read-only local Git mount belong to Indexer only. Restrict Indexer `/index` and `/mcp` to private administrator ingress with validated TLS and appropriate network access controls; expose Query `/api/v1` and `/mcp` through authorized TLS ingress. Localhost HTTP smoke does not establish remote TLS or ingress safety. Do not put an Indexer URL or its environment/mounts in Query.

Indexer config uses `semantic.source-admin-root` (`SEMANTIC_SOURCE_ADMIN_ROOT`, default `/data/source-admin`) and `semantic.source-published-root` (`SEMANTIC_SOURCE_PUBLISHED_ROOT`, default `/data/source-published`). For each approved ID configure `semantic.repositories.<id>.url`, `.default-branch`, `.display-name`, and optional `.project-guide-path` on **Indexer only**. Query config `semantic.query.source.published-root` (actual YAML environment placeholder `SEMANTIC_SOURCE_PUBLISHED_ROOT`), `rg-executable` (`SEMANTIC_QUERY_RG_EXECUTABLE`, default `/usr/bin/rg`) and explicit `allowed-repositories` list. Query also supports `read-content-bytes`, `search-timeout`, `read-timeout`, `list-timeout`, `max-active-searches`; shipped values are 65,536 bytes, 5s, 2s, 2s and 2. Empty allowlist denies all repositories, regardless of registered IDs. Remove old repository/package/class/method restriction settings, including structured list entries, only after explicitly approving a new repository/source scope; retained nonempty obsolete restrictions reject Query during context initialization, before the web server binds, even if the same repository is explicitly allowed under the new source policy. Query has no private Git URL/token.

Indexer index property is `semantic.index-published-root`; Query's is
`semantic.query.index.published-root`. Both packaged YAML files accept
`SEMANTIC_INDEX_PUBLISHED_ROOT`; blank derives a sibling named `index-published`
beside the configured source published root. For default source paths this is
`/data/index-published`. If Query mounts source at a different container path,
explicitly set the index path to its **Query-visible** read-only mount. Namespaces
must be separate and absolute; do not put index inside source/private storage.
An absent default index root is normal source-only operation, not a reason to
mount private Indexer storage into Query.

For an agent-operated end-to-end flow, load
[onboard-repository](../../skills/onboard-repository/SKILL.md); its explicit
authorization and dependency-preparation steps do not grant service mutation.

For onboarding, use separate external configuration files (paths are examples,
not loaded automatically). A synthetic registration on Indexer:

```yaml
semantic:
  repositories:
    fixture:
      url: https://example.invalid/team/fixture.git
      default-branch: main
      display-name: Fixture
      project-guide-path: PROJECT_GUIDE.md
```

The matching **Query-only** scope:

```yaml
semantic:
  query:
    source:
      allowed-repositories: [fixture]
```

Load each file only into its own process using Spring external configuration,
for example `--spring.config.additional-location=file:/run/config/indexer.yml`
or `file:/run/config/query.yml`. Substitute the approved origin/IDs locally;
the example URL is deliberately non-routable. Inject tokens separately through
the secret store. An ID registration plus Query allowlist permits discovery,
not preparation; an empty allowlist denies all. No shared environment file,
Git credential mount or Indexer endpoint configuration belongs in Query.

### Launch and connect

Build matching artifacts with Java 21 and Maven 3.9.x from the reactor root:
`mvn --batch-mode --no-transfer-progress package`. This produces
`semantic-indexer/target/semantic-indexer-0.0.1-SNAPSHOT.jar` and
`semantic-query/target/semantic-query-0.0.1-SNAPSHOT-exec.jar`.
See [testing](testing.md) before admitting a local build; packaging alone is not
deployment acceptance.

After provisioning the separate users/mounts above, launch each artifact in its
own service environment. These are two independent foreground commands, not a
shared shell environment. The external files must contain the approved roots,
registration and Query allowlist described above; only the corresponding token
is injected into each process by the secret store.

```bash
# Writer environment: private/admin token and any required Git credentials only.
java -jar semantic-indexer/target/semantic-indexer-0.0.1-SNAPSHOT.jar \
  --spring.config.additional-location=file:/run/config/indexer.yml \
  --server.address=127.0.0.1 --server.port=8081

# Reader environment: read token only; published children mounted read-only.
java -jar semantic-query/target/semantic-query-0.0.1-SNAPSHOT-exec.jar \
  --spring.config.additional-location=file:/run/config/query.yml \
  --server.address=127.0.0.1 --server.port=8080
```

For an already authenticated Codex CLI supporting these flags (tested client
version: 0.160.0), register **only Query** for one invocation. Inject
`SOURCE_QUERY_TOKEN` from the read-token secret store into this client process;
do not place the secret value in the command or global client configuration.

```bash
codex --no-daemon exec --ignore-user-config --ephemeral \
  --sandbox read-only --skip-git-repo-check \
  -c 'mcp_servers.source.url="http://127.0.0.1:8080/mcp"' \
  -c 'mcp_servers.source.env_http_headers={"X-Api-Token"="SOURCE_QUERY_TOKEN"}' \
  'Use source MCP list_repositories without a name filter and follow all pages.'
```

The client performs Streamable HTTP initialization and carries the token header.
For remote access replace localhost with the approved Query TLS endpoint.
Private Indexer `http://127.0.0.1:8081/mcp` is a separate administrator connection
using the admin token; never add it to an ordinary read-only business-query
client. Registration still does not prepare source. For business questions,
explicitly load the [query skill](../../skills/query-business-code/SKILL.md) in
the client, or use the [portable file-reading instructions](../../README.md);
installing a server or skill does not guarantee automatic model triggering.


### Optional human-reviewed business-discovery hint

For a short business summary and query keywords, use the [description skill](../../skills/describe-repository/SKILL.md); business questions use the separate [query skill](../../skills/query-business-code/SKILL.md). A draft remains local and `DRAFT`/`NOT_VERIFIED`. A person must explicitly approve the exact summary and keywords for the stated analyzed revision. That content approval is distinct from authorization to edit Indexer configuration; an authorized operator or separately authorized agent may then enter the reviewed values in the external Indexer configuration. Do not parse/import the Markdown, register a repository, prepare/fetch source, or publish automatically.

Synthetic example only; `fixture` is not a configured repository and the SHA is a format-only placeholder, not a claim that this commit exists:

```yaml
semantic:
  repositories:
    fixture:
      navigation-hint:
        review-status: APPROVED
        summary: "Synthetic fixture: member qualification rules have source entry points."
        keywords:
          - "會員等級"
          - "qualification"
        analyzed-revision: "0123456789abcdef0123456789abcdef01234567"
```

`navigation-hint` is optional. With it absent, no hint is published. A present hint must explicitly set `review-status`; blank or unknown status rejects startup publication before changing the existing registry—it is not silently treated as DRAFT or as removal. Explicit `DRAFT` publishes no hint content. `APPROVED` is the operator's review declaration, not server verification of truth; it requires a 1–240 Unicode-code-point summary and 1–12 keywords after ordinal deduplication preserving input order (each 1–32 code points), valid single-line Unicode without controls or U+2028/U+2029, and a full 40-character lowercase SHA-1 `analyzed-revision`. No values are silently truncated; a complete serialized hint is at most 2 KiB and the whole registry at most 1 MiB.

Only an explicit Indexer start/restart publishes the external configuration; a draft, edit to `.local`, Query call, or Query restart is not an import or refresh. Removing the hint or setting it to DRAFT takes effect only after a valid explicit publication. This updates the mutable `repositories.json` catalog, not source/index artifacts. After a successful update or removal, old repository-list cursors bound to the previous registry digest are invalid; start a new listing. Query returns hints only for repository IDs on its explicit allowlist. A hint can accompany a registered but `NOT_PREPARED` repository, but it never grants source access or triggers preparation. `analyzed-revision` describes the reviewed text's basis only; it does not select current, prove that source SHA is published, or authorize it.

The hint is an optional descriptor/list field; it does not change public source format 1/policy 2, private source-job format 2, or index format/profile, and does not rewrite immutable source trees, receipts, or sealed SQLite generations. Deploy matching Indexer and Query releases; older binaries are not promised to understand the added field.

For business discovery, list repositories without `nameFilter` and follow every page. `nameFilter` matches display names only, never keywords.


### SQLite JNI prerequisites

Query's Temurin image provides `/tmp/sqlite-native`, owned by UID:GID
`10002:10002`, mode `0700`, with
`JAVA_TOOL_OPTIONS=-Dorg.sqlite.tmpdir=/tmp/sqlite-native`. Preserve that property
when replacing Query's options; unpackaged Query needs the same owned private path.
Indexer does not initialize SQLite in its parent process. Every syntax/Relations
build child receives a fresh mode-`0700` temp directory under the parent's
`java.io.tmpdir` (default `/tmp`), passed explicitly as the child's Java/SQLite
temp path. Parent SQLite properties and `JAVA_TOOL_OPTIONS` are not forwarded.
SQLite's bundled JNI library requires matching CPU/libc and writable/traversable
storage where native mapping is permitted, not a `noexec` mount. For read-only
container roots, provide separate writable/executable tmpfs storage: Indexer
`/tmp` owned by `10001:10001`; Query `/tmp/sqlite-native` by `10002:10002`, with
mode `0700`. Never share it or use source/index publication for extraction.
Query opens sealed databases immutable/read-only, creates no database or
journal/WAL/SHM sidecars, and needs no write permission on index mounts.
Native bootstrap is lazy and index-local: source startup/tools and subsequent
source preparation must remain usable if native loading fails.

Indexer publishes a sanitized `repositories.json`; registration is **not** preparation. The private bare repository/jobs/staging live under `source-admin`; published `repositories.json`, `<id>/state.json`, `<id>/read.lock`, and `<id>/revisions/<sha>/{manifest.json,inventory.jsonl,tree/}` live under `source-published`. Public manifest/state/result format remains **1**; source lifecycle policy is **2**, and private preparation jobs remain **2**. Query admits only an allowlisted ID and a revision in atomic state membership whose receipt/manifest digest and versions match. A directory on disk alone, including an orphan after interruption, grants no read. Staging and final revision directory are on the same filesystem: Indexer flushes the complete tree/metadata, atomically renames the revision, then atomically replaces state containing membership and current together. No copy fallback or in-place overwrite of an existing published SHA.

Index membership is separate: `index-published/<id>/state.json` selects a
generation per SHA; `revisions/<sha>/<generation>/{manifest.json,index.sqlite}`
are sealed immutable artifacts. The manifest binds repository/origin/SHA,
source-manifest and inventory digests, canonical UUID generation, exact parser
profile, coverage, database size/digest and preparation time. State membership
and a full receipt (`manifest`, `manifestDigest`, `jobId`, `requestId`) authorize
selection/recovery; a leftover directory alone never does. New private format-2
semantic jobs live in `source-admin/index/jobs/<id>/<requestId>.json`, with frozen
analysis intent; legacy format-1 jobs remain recoverable. Stage in
`source-admin/index/staging/<generation>` and keep cleanup intents in
`source-admin/index/retention/`. Index manifest/state format remains 1; schema/policy
profiles are separately versioned. Source public format 1/policy 2 and private
source-job format 2 are unchanged.

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

Private **source** jobs use format version 2 and retain the accepted origin
through every transition. Public source manifest/state/descriptor and
HTTP/MCP source results retain version 1; jobs add an operation discriminator.
There is no private-job-v1 decoder or automatic adoption of populated unbound
namespaces. For pre-release unbound source storage, preserve the old roots for a
matching rollback, bootstrap new private/published roots, and explicitly prepare
the required revisions again. Do not run an old Indexer against the new private
job format or mix old unbound data into the new namespaces.

## Application capacity admission

Indexer supports two independent, nonnegative integer byte thresholds:

| Indexer property | Environment | Default |
| --- | --- | --- |
| `semantic.source-capacity.max-managed-bytes` | `SEMANTIC_SOURCE_CAPACITY_MAX_MANAGED_BYTES` | `0` (disabled) |
| `semantic.source-capacity.min-free-bytes` | `SEMANTIC_SOURCE_CAPACITY_MIN_FREE_BYTES` | `0` (disabled) |

The managed-byte check sums regular-file logical sizes in private admin, source
published and index published roots, including Git objects, durable source/index
jobs, staging, retained source and all retained SQLite generations. Equal/nested
roots are counted once. An absent unconfigured default index root contributes
no artifact bytes; enabled measurement of unsafe paths fails closed.
Measurement reads no file bodies and follows no symlinks. Logical sizes are not allocated
disk blocks, filesystem metadata or a hard-link-aware physical usage calculation.
Minimum free space uses the usable space of each distinct filesystem backing
the configured roots. Usage equal to the maximum and free space equal to the
minimum are allowed. Negative configuration is rejected; zero disables only its
own check, and both zero skip capacity measurement.

The existing single worker checks before Git resolution (including known-SHA
reuse), after durable revision pinning, and after export before sealing. HTTP
202 still means durable **acceptance**, not available capacity or publication.
`get_job` through either HTTP or MCP reports `FAILED` with `CAPACITY_EXCEEDED`
when a threshold rejects the work, or `CAPACITY_UNAVAILABLE` when enabled
measurement cannot be completed safely. Current/membership and previously sealed
source remain unchanged. A receipt already committed before interruption still
recovers to `COMPLETE`; the capacity policy does not undo a committed publication.
Semantic jobs use the same capacity thresholds before/during build and before
sealing. Count/declaration/database limits or capacity refusal fail that job and
preserve the prior healthy selected generation and source current. Indexing adds
no reservation, hard quota, forced GC or extra dispatcher.

These are checkpoint admission checks, **not a reservation or filesystem hard
quota**. Git fetch/export can exceed a threshold before the next check; publication
metadata and unrelated writers can consume space afterward. Query is not gated,
no extra worker is added, and capacity pressure never shortens the 30-day retention
window or forces GC. Rejected work can leave fetched objects or private staging;
it does not authorize orphan deletion or rewrite published data.

Recover the original request with `get_job` before taking further action. After
confirming a terminal refusal, an operator can provision space or adjust/disable
the thresholds and restart Indexer, then submit an explicitly new request ID.
The original FAILED job remains recoverable; resubmitting its ID remains
`REQUEST_ID_REUSED`. Inspect unsafe storage rather than silently bypassing an
unavailable measurement. Capacity configuration does not change source/job formats
or require rewriting existing policy-2 publications.

## Revision retention and recovery

Policy 2 requires **fresh private and published roots** and matching Indexer/Query
releases. Explicitly prepare each required exact SHA; do not rewrite policy-1
manifests, migrate old lifecycle metadata, or run an old Query on this namespace.
Public formats/results remain 1 and preparation jobs remain 2; lifecycle JSON is
private format 1 and is not an old-job compatibility decoder.

When B replaces current A, A receives at least `30 × 24h` from that UTC publication
instant, not from its commit/preparation date. Current is never reclaimed. Making
A current again clears its old retirement time; its next replacement starts a
fresh full window. Repeating the same current does not reset other retirements.
Missing post-publication timestamps receive a fresh window at reconciliation;
corrupt/missing initialized records fail closed rather than authorize deletion.

Only Indexer deletes snapshots, on its existing idle dispatcher after startup
recovery. Waiting preparation takes priority. Configure only:

| Indexer property | Environment | Default |
| --- | --- | --- |
| `semantic.source-retention.enabled` | `SEMANTIC_SOURCE_RETENTION_ENABLED` | `true` |
| `semantic.source-retention.interval` | `SEMANTIC_SOURCE_RETENTION_INTERVAL` | `24h` |

Interval must be positive; the next scan is due after the preceding scan ends.
Busy repositories are skipped without blocking readers; continuous preparation
or readers can extend retention. Retention itself provides no free-space
guarantee; capacity admission is separate. Missed daily scans are not replayed,
and no additional GC service/API is provided.

Indexer creates each stable `<id>/read.lock` before advertising the repository.
Query opens it read-only and holds a shared OS guard from state admission through
the final source bytes and ripgrep termination/reaping. Same-JVM readers share
one channel until the last owner closes it. GC obtains a nonblocking exclusive
guard, rechecks eligibility and atomically withdraws membership, then releases
the guard **before** physical deletion. Missing/unsafe locks or unsupported
locking fail closed. This deployment supports local POSIX filesystems, not NFS.
Query retains the shared guard and search slot until all spawned processes and
source-reading workers actually terminate, even when bounded cleanup returns an
error. A termination request alone does not make the revision collectable.

`source-admin/repositories/<id>/retention.json` records origin-bound retirements;
`pending-delete.json` records at most one origin/digest-bound deletion intent.
Recovery only follows a validated intent, never an unmarked orphan. A still-
published intent is revalidated; a no-longer-eligible/current intent is cancelled.
A withdrawn intent resumes the same managed path, including partial deletion.
The pending intent fences same-SHA sealing/publication until durable cleanup.
Do not manually clear an intent or re-advertise a partially deleted tree.
An incomplete/corrupt initialized lock/retention pair or invalid pending metadata
rejects **Indexer startup as a whole**, before repository advertisement. After a
successful startup, a repository's GC metadata error stops that repository's
collection rather than granting deletion authority. Existing independent Query
reads do not depend on Indexer startup. Diagnose the original service-owned
namespace; do not recreate a stable lock inode, clear markers, or automatically
adopt incomplete/populated storage. Interrupted first bootstrap can require a
new, explicitly provisioned namespace after ownership confirmation.

Disabling GC stops new deletion and pending physical recovery. Withdrawn revisions
stay withdrawn; re-enabling allows cleanup. A reclaimed context or continuation
returns existing `REVISION_NOT_PREPARED` (HTTP 404/MCP error), never latest.
Sessions/cursors do not extend retention between requests. After pending cleanup,
explicitly prepare the same exact SHA if the approved remote still supplies it.

Index operations keep the **same source repository shared guard** through digest,
SQL materialization and actual connection/worker cleanup, plus the shared search
permit. Source GC cannot reclaim the source or companion indexes mid-read.
Selected generations for readable source are protected. Obsolete, non-selected
generations have **no minimum retention TTL**: the idle collector may withdraw
them on its next eligible scan under the repository exclusive guard, using
identity/digest-bound intents and a membership recheck. The source revision's
30-day guarantee does not extend to superseded index generations. Cursors do not
lease storage between calls. No independent GC worker or operator-delete API exists.
Source deletion creates a companion index intent **before** source withdrawal.
The original source intent remains until source and index withdrawal/physical
cleanup are durably complete, fencing same-SHA republication throughout.
Recovery follows only validated managed targets and tolerates already-deleted
artifacts; do not clear intents, manually reselect a generation, recreate stable
lock inodes or infer membership from directories. Disabling retention stops both
new and pending source/index physical cleanup. Rebuild requires readable source
at the exact published current revision. If source expired, an operator may
explicitly prepare its exact SHA to restore source reads; that historical
preparation does not promote current or authorize a new index build.

Search private Indexer logs by `gcRunId` and `event=source_gc_*`: start/end,
deleted, skip (`reason=READ_IN_PROGRESS`), failure and recovery. Failures carry
`repositoryId`, optional `revision`, `stage`, stable `errorCode` and exception type,
not raw messages/URLs/tokens/paths. Recovery retains the original intent's run ID.
Deleted events report actual files and logical bytes deleted **in that attempt**,
plus duration; bytes are not filesystem space freed or an invented pre-crash total.
End summaries distinguish success/partial/failed and protected/busy/failure counts.
Interrupted/disabled incomplete runs report `result=partial` with
`stopReason=INTERRUPTED` or `DISABLED`, never complete success.
Log delivery is not the durable recovery authority or an exactly-once guarantee.


## MCP session lifecycle

Query `/mcp` uses stateful Streamable HTTP. Each independent client initializes its
own session, retains the server-issued `Mcp-Session-Id`, sends
`notifications/initialized`, and includes that session ID and the negotiated
`MCP-Protocol-Version` on subsequent requests and notifications. Continue sending
`X-Api-Token` on every request; a session ID is not a substitute for authorization.
Use a Streamable HTTP MCP client that manages this lifecycle rather than issuing
uninitialized `tools/call` POSTs.

Query JSON-RPC request IDs are limited to 512 JSON-serialized UTF-8 bytes,
including string quotes and escapes. Numeric and bounded string IDs are
supported. An oversized ID is rejected with bounded HTTP 400 before SSE response
creation or tool dispatch; it is not echoed into a response, replaced with a
different ID, or sent as an invalid null-ID MCP response. The session remains
usable. This wire ID is separate from Indexer's durable UUID `requestId`.
Complete source tool results reserve space for this bounded outer envelope as
well as both MCP text and structured content.

JSON-RPC request IDs are scoped to a session: independent clients may reuse the
same ID without rejecting or cancelling each other's calls. Send
`notifications/cancelled` with the owning session and request ID to stop its work.
Close an unused session with `DELETE /mcp` carrying the same headers. DELETE and
graceful shutdown interrupt owned work and await terminal cleanup, with a
six-second lifecycle deadline. DELETE does not report success if cleanup exceeds
that deadline. Cancellation preserves the structured tool-error response rather
than leaving its interrupt flag set during HTTP response writes.

After Query restarts, initialize a new session. `read_source` continuation tokens
are authenticated with a process-local key and fail `INVALID_ARGUMENT` after
restart or modification; they are not durable bookmarks. Restart the read with
the same exact source context and explicit retained `indexGeneration` when
needed, rather than removing pins or selecting current. Already-published source
and retained index generations remain readable and require no new preparation.
Indexer retains its separate stateless preparation endpoint and admin token.

This deployment leaves session idle eviction disabled, so clients must close
unused sessions with `DELETE`; otherwise they remain until server shutdown.
The pinned Spring AI 2.0.1 provider supports an optional builder idle timeout and
a separate session cap (default 100,000), but neither that cap nor the two-search
limit establishes tested capacity. This service does not enable an idle timeout.


## Current-source and upgrade migration

`current` is the SHA from the most recent successful explicit `prepare_source`
request that omitted `revision`: that operation fetches the configured
default-branch HEAD, pins it, and atomically publishes it as current. Current is
not a live branch lookup, commit-time maximum, or automatic refresh. `get_context`
and every Query read are discovery/read-only operations and never fetch or
advance current.

An explicit full lowercase SHA in `prepare_source` prepares or reuses that
revision's readable source membership only. It never changes current or its
publication metadata, even when the SHA equals current. With no current yet, an
exact-SHA source can still be read by that exact context, but it does not establish
current.

Before declaring an existing deployment to use the new current contract, an
authorized operator must complete one successful baseline refresh:

1. Save a **new source request UUID** and submit `prepare_source` without
   `revision`. Use the original UUID to recover uncertain acceptance, and wait
   for that job to reach `COMPLETE`.
2. Capture the exact `resolvedRevision` from that source job and check
   `get_context` for the same repository. Do not report the baseline refreshed
   unless the returned current context is that exact SHA. On failure, the prior
   current and READY source remain; diagnose the original job rather than
   claiming the migration succeeded.
3. If a new syntax index is required, save a **different new UUID** and submit
   `prepare_semantic_index` with that exact resolved SHA. This is a second,
   explicit operation, not a source-job child. Wait for its own job result.
   If current moved between the two calls, this request is rejected before
   acceptance with `INDEX_NOT_CURRENT` (HTTP 409); it is not queued or retargeted.
   After a legitimate default-branch refresh and a new current check, submit a
   distinct index request with its own persisted UUID if needed.

Drain or explicitly account for durable pending jobs before rollout. On the new
release, old `COMPLETE` and `FAILED` results remain unchanged. An old `RUNNING`
source or index job recovers as `COMPLETE` only when its actual committed receipt
and matching completion evidence validate; an uncommitted interrupted job fails
under its original request identity, without orphan adoption or replay. A source
receipt alone, an old membership entry, or `current.publicationJobId` alone does
not prove that a particular historical job committed.

An old `ACCEPTED` explicit-SHA source intent resumes as membership-only
publication, preserving its UUID, SHA, origin and expected-current fence; it
does not keep the former arbitrary-SHA promotion behavior. An old `ACCEPTED`
semantic intent resumes only if its exact SHA is still current and source
identity matches. Otherwise that original UUID becomes a durable
`INDEX_NOT_CURRENT` failure; no replacement job is created. A previously
committed semantic receipt still recovers to `COMPLETE` if its source is now
historical. An old current pointer that was originally set by explicit-SHA source
preparation is not retroactively rewritten; perform the successful no-revision
baseline refresh above before relying on the new contract.

Current-only policy applies to **new semantic-index preparation**, not Query
reads: readable historical source remains readable, and retained historical
indexes/generations remain usable by exact SHA. Do not rebuild a non-current SHA
under the new policy; wait until a legitimate default-branch refresh makes it
current again. Do not rewrite old source trees, receipts, manifests or sealed
databases. Preserve existing service/data on failed migration, and perform
deployment actions only with separate operator authorization.

## Prepare, discover, navigate

Use private Indexer `/mcp` (`prepare_source`, `prepare_semantic_index`, `get_job`) or equivalent HTTP below, with `X-Api-Token: <admin-secret>`. Save a **canonical lowercase UUID** durably before each preparation POST; source and semantic requests use separate UUIDs. Example bodies omit secrets:

```http
POST /index/repositories/video/source
Content-Type: application/json

{"requestId":"dcc061e8-d89f-46e0-ae76-d336c351d738"}

GET /index/repositories/video/jobs?requestId=dcc061e8-d89f-46e0-ae76-d336c351d738
```

That first `prepare_source` example deliberately omits `revision`: it is the explicit default-branch refresh step. Optional `revision` is instead an exact full lowercase 40-character commit already obtainable in the approved repository, never a branch/tag/path/ref. An explicit SHA publishes membership only and cannot update current. No URL, checkout path or arbitrary branch comes from the caller. HTTP `202` means durable acceptance; `ACCEPTED` may not yet have a resolved SHA. Capture `resolvedRevision` and `jobId`; use `get_job` with exactly one `jobId` or original `requestId`, scoped to the same repository, and wait for `COMPLETE`. The source job UUID is not reused for semantic preparation; the current-source migration section gives the two-step baseline/index recipe. The disposable example UUID is not a production identity. An original request reused, even with the same payload, is not a second submission (`REQUEST_ID_REUSED`); an active repo job is rejected separately. If the POST times out or its result is lost, retain and look up the original requestId. A missing lookup leaves acceptance unknown: continue original lookup or stop, never automatically resubmit. After inspecting an explicit terminal failure, a newly authorized attempt gets a new UUID. One Indexer process, one dispatcher lane, at most one active job per repository; do not run a second writer.

Read-token users connect to Query `/mcp` or nine HTTP endpoints; all require `X-Api-Token: <read-secret>`:

```http
GET /api/v1/repositories
POST /api/v1/context                 {"repositoryId":"video"}
POST /api/v1/context                 {"repositoryId":"video","revision":"d366278ff697b2ac7d0510830a840a475ec31794"}
POST /api/v1/files                   {"context":{"repositoryId":"video","revision":"d366278ff697b2ac7d0510830a840a475ec31794"},"directory":""}
POST /api/v1/search-text             {"context":{"repositoryId":"video","revision":"d366278ff697b2ac7d0510830a840a475ec31794"},"query":"upload"}
POST /api/v1/source                  {"context":{"repositoryId":"video","revision":"d366278ff697b2ac7d0510830a840a475ec31794"},"path":"src/main/java/com/example/video/VideoController.java"}
```

The sample SHA below is **A from a disposable observed fixture**, not a promise that it is current; replace it with the exact context returned by `get_context`. An omitted `get_context` revision observes the current SHA from the most recent successful default-branch refresh; an explicit SHA requires exact published membership and never falls back to current. First list may show `NOT_PREPARED`, `PREPARING` or `FAILED` without a revision. Ready source has `sourceStatus: READY`; independent `semanticStatus` is `NOT_READY` without an index, `READY` with compatible index metadata, or `UNAVAILABLE` for known invalid metadata. The optional index summary supplies generation/profile/coverage, not proof that every Java file parsed or a JDBC health check. `get_context` performs no fetch or preparation. Keep each returned `{repositoryId, revision}` fixed for every subsequent read.

`list_repositories` and `list_files` default to 20 items, maximum 100. `list_files` defaults to `depth: 1`; `depth: 2` emits flat preorder with at most 12 immediate children for each direct-child directory. Each directory reports `childPreview: COMPLETE`, `ELIDED` or `NOT_EXPANDED`; `ELIDED` means explicitly call `list_files` on that directory. The global page limit counts all emitted entries, including directories, and its cursor resumes at the actual next item even if a preview was paused. Cursors bind exact repo/revision, directory, depth, preview and limit. Complete HTTP/MCP results are bounded at 512 KiB; no unbounded tree or fabricated child count is returned.

`read_source` accepts `view: auto|raw|summary` (default `auto`) and at most one explicit selector: `startLine`, 1–20 inclusive `ranges`, or `tailLines` (1–500). `startLine` selects through EOF. `maxLines` is a per-page limit (default 200, maximum 500), not a cap on the full selection; long selections continue by cursor. `contextBefore`/`contextAfter` are each 0–20, only for `ranges`; raw requires both zero. The request and every cursor continuation must retain the same context, path, view, selector, line limit, context and optional generation pin.

Read cursors authenticate their initial effective view, generation and projection
with HMAC-SHA256. The key is generated per Query process and is never stored in a
published namespace or derived from a client credential. All reader instances
within that process accept its cursors; another process or restart rejects them
explicitly. Exact context and generation pins, not a cursor, are the durable way
to restart navigation. Authentication does not replace revision admission or
the validation of the cursor's actual raw position and selected windows.

`raw` always reads original source without depending on an index. `auto` with any explicit selector returns raw; without selectors it uses a summary only for eligible Java with published fold metadata, otherwise returns raw and a `summaryOutcome` explaining why. `summary` requires no selector and requests the whole-file structural view; it returns `SOURCE_SUMMARY_UNAVAILABLE` (HTTP 409) with a bounded `reason` instead of silently falling back. A bare auto summary requires at least 100 original content lines; explicit summary has no line-count threshold. Fold projection is limited to eligible parsed Java methods and ordinary/compact constructors: it elides only body-interior runs of at least four complete original LF lines, preserving the brace lines. It does not summarize comments, imports, fields, lambdas or class bodies. A generation/profile pin is allowed only with summary; old schema/policy profile 1 remains usable for syntax queries but has no fold capability.

`SourceResult` has no top-level continuous `content` alias. It reports exact context/path, `blobId`, `contentDigest`, `byteLength`, requested/effective view and selection, `summaryOutcome`, optional `indexGeneration`/`profile`, ordered `segments`, `hasMore`, `nextCursor` and `stopReason`. A `SOURCE` segment carries lossless original text and its exact half-open UTF-8 byte and one-based LF line span, plus mid-line flags. An `ELISION` segment carries the omitted original span, `foldKind` and `declarationId`, but no source content. Selection gaps are not elisions; intentional folding does not itself mean another page exists. Each page has at most 500 segments, at most 65,536 source-content bytes and the requested `maxLines`; full HTTP/MCP serialization is at most 512 KiB. `stopReason` distinguishes completion from line/content/segment/wire budgets. Cursor continuation pins the same source digest, request/view/effective selection and generation; it never changes an auto-selected raw view or summary generation.

`search_text` remains single-line, literal, case-sensitive (maximum 256 Unicode code points), with optional narrowing `directory`/`filePattern`; it has no regex or continuation cursor. If `truncated=true`/`scanComplete=false`, narrow the query/path and do not claim absence. It shares at most two concurrent permits and a 5s deadline; list/read keep their 2s deadline. Search columns are one-based UTF-16 code units.

`filePattern` matches repository-relative paths; `directory` narrows that same scope and does not rebase the glob. For example, directory `src` with pattern `src/*.java` searches Java files directly under `src`. Line coordinates count original LF bytes: LF belongs to the line it ends, a final LF creates no fictitious line, an empty file has no content lines, and final bytes without LF are still one line. A selector beyond EOF yields an empty effective selection with no fabricated citation span; `tailLines` selects the last N existing lines (or the whole file when shorter).

Search uses ripgrep's path-sorted directory traversal so the bounded result window and HTTP/MCP order do not depend on worker scheduling. Each search checks complete inventory digest/order once using one read-only file handle; bounded positional lookups on that verified handle validate candidate windows without retaining a whole-repository index. Each unique authorized file is hashed once, and untracked physical hits cannot consume the authorized result limit or cause a false complete scan. Directory traversal order is not global lexicographic file-path order (for example, `a/z.java` can precede `a.java` in rg output); preserve the actual rg prefix. The two-search concurrency limit and original 5s wall deadline still apply; there is no heap sort or search continuation.

The search permit is acquired before revision admission and inventory I/O, remains
held through subprocess cleanup, and is shared by HTTP and MCP. Excess requests
receive `SOURCE_BUSY` without entering admission. Repository listing parses its
descriptors and computes its cursor binding from the same registry byte snapshot;
if the registry changes between pages, obtain a new listing rather than reusing
the rejected cursor.
Business-discovery `list_repositories` also has a 160 KiB complete application-response budget (HTTP/MCP transport: 512 KiB). Pages contain whole items and may end before `limit`; cursors bind the unchanged request and registry digest. If one item alone exceeds the application budget, `SOURCE_RESULT_TOO_LARGE` is HTTP 413 / the equivalent safe MCP error. A valid hint update/removal changes that digest, so discard old list cursors and restart discovery from page one.

Only tracked regular supported text at the pinned commit is readable. The same policy excludes `.git`, `target`, `build`, `.gradle`, `node_modules`, `generated` at any depth and `*.class`; direct read cannot bypass it. Symlinks are not followed, submodules are not initialized, binary/unsupported encoding/path/LFS pointers are explicitly unsupported; no filters, hooks, LFS hydration or build scripts execute. A guide optionally committed at configured `project-guide-path` is `AVAILABLE`, `MISSING`, `INVALID` or `DISABLED`, always `freshness: NOT_VERIFIED` if represented. It is a navigation hint, not verified business truth, and its absence never blocks source readiness. See the [external authoring prompt](repository-context-prompt.md).


## Prepare and navigate the Java index

Submit a new semantic-index request only for the exact SHA returned by the
completed no-revision source refresh when that SHA is still the published
current. The exact lowercase 40-character SHA is required; this operation does
not fetch, select current for you, or change current:

```http
POST /index/repositories/video/semantic-index
Content-Type: application/json

{"requestId":"a50e3b81-e52b-435b-96ae-153aace21bcf","revision":"<resolvedRevision from the completed current-source job>"}

GET /index/repositories/video/jobs?requestId=a50e3b81-e52b-435b-96ae-153aace21bcf
```

Use your own approved ID/SHA, not these synthetic examples. HTTP 202 is durable
acceptance, not index readiness. `get_job` accepts **exactly one** `requestId` or
`jobId` and returns `operation: prepare_source|prepare_semantic_index`.
The shared per-repository admission window rejects request reuse across both
operations and permits at most one active source or semantic job. A lost response
is recovered by the original request ID; unknown acceptance is never retried
automatically or replaced with another UUID. Inspect COMPLETE receipt or explicit
terminal failure before authorizing a new request. A committed full semantic
receipt wins over a failed terminal-job write/restart or later capacity refusal;
uncommitted interrupted RUNNING fails `WORKER_INTERRUPTED`. Recovery ignores
regular service-owned `.source-*.tmp` atomic-write residues without adopting or
deleting them; malformed committed jobs, symlinks and unexpected directory entries
remain fail-closed. No orphan adoption, automatic replay or source fetching is
part of semantic recovery.

Three read operations (also MCP `search_code`, `get_outline`, `list_entry_points`):

```http
POST /api/v1/search-code
{"context":{"repositoryId":"video","revision":"d366278ff697b2ac7d0510830a840a475ec31794"},"query":"VideoController","match":"exact"}

POST /api/v1/outline
{"context":{"repositoryId":"video","revision":"d366278ff697b2ac7d0510830a840a475ec31794"},"path":"src/main/java/com/example/video/VideoController.java"}

POST /api/v1/entry-points
{"context":{"repositoryId":"video","revision":"d366278ff697b2ac7d0510830a840a475ec31794"}}
```

`search_code` searches declaration names, **not** body text or references: mandatory
`match: exact|prefix`, literal case-sensitive/BINARY matching, 1–256 Unicode code
points with no CR/LF/NUL. `%`, `_`, `*` are ordinary characters, never regex/glob.
Optional `kinds`, exact lexical `owner` and repository-relative `directory` narrow
it. `get_outline` enumerates a single inventory-authorized file's declarations
and explicit `file` outcome; nonexistent paths remain `SOURCE_NOT_FOUND`, while
existing non-Java/unsupported/too-large/parse-failed files are disclosed outcomes,
not invented empty successful parsing. `list_entry_points` narrows syntax
candidates by `candidateKinds` and `directory`.

All three return exact context, `indexGeneration`, profile, Java coverage,
`analysisLevel: SYNTAX`, complete rows and page metadata. Declarations retain
symbol/parent IDs, written owner/signature, path, Git blob ID, content digest,
byte length and source/name ranges. Byte spans are half-open **raw UTF-8 bytes**;
lines are one-based LF lines, preserving CRLF, tabs and raw Unicode escapes.
Use `read_source` at that identical SHA to confirm and cite evidence. Never treat
a written signature/owner as a resolved JVM descriptor/type or business fact.

Syntax-only builds use JavaParser `3.28.2`, `JAVA_21`, Unicode escapes-enabled,
tab-size-1 schema/policy `2/2`; explicitly enabled Relations builds use the
`JavaParserSymbolSolver` `3/3` profile. Readers and committed-receipt recovery
support matching known profiles `1/1`, `2/2` and `3/3`. Profile 1 has syntax only,
profile 2 adds folds, and profile 3 adds Relations. Discovery/results disclose
these capabilities; sealed old generations are never upgraded in place.
The service performs one [JLS 3.3 translation](https://docs.oracle.com/javase/specs/jls/se21/html/jls-3.html#jls-3.3) with
exact raw-boundary mapping, including backslash eligibility and repeated `u`
markers; JavaParser's duplicate Unicode preprocessing is disabled. Signatures
exclude comments. Only written Java declarations are indexed; no
generated record accessors/default constructors, Lombok members or repository
builds. Static resolution requires explicit Relations configuration below and
never claims runtime dispatch. Syntax entry kinds are MAIN, HTTP_MAPPING, SCHEDULED
and RABBIT_LISTENER. MAIN_SIGNATURE is syntax;
FULLY_QUALIFIED/DIRECT_IMPORT/AMBIGUOUS_SIMPLE describe spelling/import evidence.
Literal annotation values are retained, expressions remain unresolved. Type and
method mappings are separate candidates, never synthesized runtime routes.

Coverage counts only case-sensitive `.java` inventory files: Java files, parsed,
failed, unsupported, too-large and declarations plus `complete`. Parse failure
produces bounded safe diagnostics and **zero** declarations/candidates for that
file. Recursive parser/AST stack exhaustion becomes `PARSER_STACK_EXHAUSTED` with
PARSE_FAILED and a fresh parser for the next file; other VM failures are not
swallowed. A legal Java UTF-16 literal or written signature can contain an unpaired
surrogate that the public Unicode-value contract cannot represent losslessly.
That file is UNSUPPORTED with `UNSUPPORTED_UNICODE_VALUE`, contributes zero facts
and does not abort indexing other files; raw source remains readable unchanged.
Paired surrogate values remain supported. Non-Java outcomes do not reduce Java coverage. Partial coverage can publish
READY; neither empty results nor `complete=true` proves runtime behavior/absence
of generated facts. One Java input is at most 1,048,576 bytes; a generation at
most 200,000 declarations and sealed SQLite at most 67,108,864 bytes. File
oversize is TOO_LARGE; generation/count/database overflow fails the job rather
than publishing a truncated generation.

Pages default to 20, maximum 100. Common result UTF-8 JSON is at most 163,840 bytes;
HTTP body/MCP CallToolResult JSON at most 524,288 bytes (excluding protocol
envelope/framing). Emit complete rows only: a byte-limited page continues after
its last emitted row; first-row/metadata overflow gives INDEX_RESULT_TOO_LARGE,
never silently skips an oversized row. `search_text` and all four index tools share
at most two permits. Five-second index deadlines include source/index admission,
digest, SQL and materialization; permits/guards outlive actual cleanup.
Parent provenance validation uses an internal projection without the parent's
signature (bounded independently to 1 MiB). An oversized parent row can therefore
fail with 413 while a bounded child remains readable; identity, source bytes,
name spelling and containment are still checked.

No generation argument selects that SHA's independently selected generation.
Optional top-level `indexGeneration` pins a canonical UUID. Continue with the
returned cursor and unchanged operation/context/filters/limit/generation; cursors
bind generation and last emitted identity, not authorization. Changing filters
or supplying an inconsistent pin is invalid. A retained historical SHA and its
selected generation remain queryable while published, even after source current
moves; no new generation may be prepared for it while it is non-current. New
builds are admitted only when the requested exact SHA is current. An expired
pin/cursor never switches generation; restart navigation explicitly on a
compatible selected generation.

| Index error | HTTP | Operator/client recovery |
| --- | --- | --- |
| INDEX_NOT_PREPARED | 409 | Source remains usable. Admin may prepare this exact SHA only if it is current; historical readers use source tools or a retained compatible generation. |
| INDEX_UNAVAILABLE | 503 | Diagnose safe private logs, JNI/temp permissions and artifact integrity; recover the original job first. A new rebuild requires that exact SHA to be current. |
| INDEX_INCOMPATIBLE | 409 | Deploy matching writer/reader profile; rebuild only the exact current SHA. Historical readers use source tools or a retained compatible generation; an authorized explicit default-branch refresh must precede any historical rebuild. Never decode another schema as this one. |
| INDEX_GENERATION_EXPIRED | 404 | Restart navigation explicitly on a retained compatible generation; no transparent cursor switch. |
| INDEX_CAPABILITY_UNAVAILABLE | 409 | This known generation has no Relations capability. Keep exact pins and use source/syntax navigation, or explicitly select a retained capable generation. |
| INDEX_SYMBOL_NOT_FOUND | 404 | The requested source/external identity is absent from this exact generation. Rediscover identities within the same context and generation. |
| INDEX_RESULT_TOO_LARGE | 413 | Narrow operation/path/filters; a smaller page cannot split one oversized row. |
| INDEX_BUSY | 503 | Shared bounded admission is full; retry the read explicitly, never prepare as fallback. |
| INDEX_TIMEOUT | 504 | Narrow read scope and inspect owned runtime resources; no empty-success result. |

MCP uses the same domain code/advice with `isError=true`, and structured content
equals parsed text content. Source admission/authorization errors still win
before index lookup. Missing/corrupt/incompatible/native-failing index must not
invalidate the five source tools or rewrite any source publication.

Query errors are safe and transport-equivalent: `INVALID_ARGUMENT` (400), `REPOSITORY_NOT_FOUND` (404, including invisible repos), `REVISION_NOT_PREPARED` (404), `SOURCE_NOT_FOUND` (404), `SOURCE_NOT_PREPARED` (409), `SOURCE_SUMMARY_UNAVAILABLE` (409 with a bounded `reason`), `SOURCE_UNSUPPORTED` (422), `SOURCE_BUSY` (503), `SOURCE_UNAVAILABLE` (503), `SOURCE_TIMEOUT` (504). MCP returns the corresponding code/message with `isError=true` and the same bounded reason where present; internal paths, credentials and raw process errors must not enter client output. `INDEX_NOT_CURRENT` (409) is an Indexer preparation error, not a Query-read denial.

## Static Relations

Relations is opt-in on **Indexer only**. Source preparation and Query never resolve
dependencies. Configure approved inputs in private external configuration before
submitting a new `prepare_semantic_index` for the exact published current SHA:

```yaml
semantic:
  repositories:
    sample:
      relations:
        enabled: true
        source-roots: [src/main/java]
        dependencies: []
```

Use 1–32 existing, canonical repository-relative, non-overlapping source roots;
`""` selects the repository root. Empty dependencies are valid but may leave
external types unresolved. Each optional dependency has a safe `id`, an absolute
local regular-file `path`, and its exact lowercase `sha256`. Supply at most 128,
in approved precedence order, with no symlinks, nested/fat JAR layouts, or implicit
application classpath. Indexer does not run Maven/Gradle, target code, annotation
processors, Git hooks, filters or dependency downloads. Ordinary multi-release
JARs require their manifest declaration; select the highest supported version at
or below Java 21. Limits are 64 MiB per JAR, 256 MiB total, 200,000 selected classes,
1 MiB per class and 512 MiB selected expanded bytes. Unsupported input refuses the
new job rather than silently choosing a different provider.

Before durable acceptance, Indexer copies approved JARs into owned
`source-admin/index/inputs/<jobId>/`, seals the source/profile/root/dependency/JDK
analysis plan, and binds the descriptor to origin/repository/job/request/generation.
Resolution precedence is Java 21 platform, admitted source catalogue, then ordered
approved JAR metadata. The Java runtime `release` and `lib/modules` digests are
part of the frozen platform identity. Changing deployment inputs cannot retarget
an accepted job. Query receives public digests/provenance, never these private paths.
Approved JAR IDs are operator labels, not reserved platform names. External target
provenance distinguishes Java-platform declarations by their module and platform
digest; a module-less JAR labeled `java21` still uses its approved artifact digest.
Input-freeze refusals, including its preacceptance capacity checkpoints, are not
accepted jobs. Capacity failures after durable acceptance remain terminal job
failures; both boundaries preserve the existing READY publication. If a submission
response is lost, inspect the original request ID rather than automatically retry.

Profile 3 manifests retain `analysisPlanDigest`; profiles 1/2 omit it. Legacy
format-1 accepted jobs keep syntax profile 2 even if configuration now enables
Relations. Committed receipts recover before consulting current configuration,
JDK identity or cleaned snapshots. Uncommitted accepted jobs with changed Java
identity fail `ENVIRONMENT_CHANGED`; corrupt/missing owned inputs fail closed.
Unknown-owner inputs are not deleted/adopted. Startup allows five seconds for an
owned worker lease to settle, otherwise fails closed; interrupted RUNNING without
a committed receipt becomes `WORKER_INTERRUPTED`, never an automatic replay.

The parent remains sole publisher. A separate trusted service JVM owns the build:
512 MiB heap, one active processor, 300-second deadline, and 64 KiB each for stdout
and stderr. Its stable private worker lease is acquired before READY; GO authorizes
build/source consumption. The child owns its lease/source guard until process exit;
the parent retains its own source guard through termination and actual reaping.
Parent stdin EOF terminates the child, including while waiting for GO or a source
guard. SQLite JNI extraction remains process-private. Capacity, output overflow,
deadline, cancellation or worker failure preserves the previous READY publication.
Do not replace lock inodes or remove snapshots while their owned worker can live.

Before creating or launching a worker's native-temp allocation, Indexer durably
records `inputs/<jobId>/worker-temp.json` with validated job ownership, the parent
temporary root and a UUID allocation ID. The private `index-worker-<jobId>-<allocationId>`
directory stays on that executable temporary filesystem, not a published mount;
its `owner.json` must match before the child accepts its JVM temp properties.
Parent cleanup follows actual reap; restart cleanup waits for the owned worker
lease and validates the exact allocation. It never sweeps a name prefix.

Input cleanup first persists `source-admin/index/input-cleanup/<jobId>.json`
outside the disappearing input leaf. This authority survives descriptor/lock
removal and is consumed only after the owned native allocation and input leaf are
gone. Recovery validates origin/job/context/path identity and preserves unknown
leaves, mismatched markers and nonregular entries rather than adopting them.

The 512 MiB limit is the child Java heap, not total deployment memory: budget
Indexer/Query heaps, native mappings and filesystem cache separately. Frozen JAR
copies, normalization scratch, source trees, staging and retained generations also
coexist; the 64 MiB per-generation database cap is not a total storage quota.

To stop new Relations builds, disable the repository's Relations setting; new
syntax-only jobs use profile 2, while already accepted jobs retain their frozen
intent and existing profile-3 generations remain unchanged. This does not downgrade
private semantic job format 2. Keep compatible readers/writers for those records;
never point an older Indexer at them or rewrite manifests/jobs to mimic older
formats. A source-only rollback must leave semantic job/index storage untouched.

MCP `find_relations` and `POST /api/v1/relations` require exact `context`,
canonical UUID `indexGeneration`, a tagged `anchor`, and `direction` on every page:

| Anchor | Fields | Directions |
| --- | --- | --- |
| `SOURCE_SYMBOL` | `symbolId` from this generation | `INCOMING`, `OUTGOING` |
| `EXTERNAL_TARGET` | `targetId` from this generation | `INCOMING` |
| `FILE` | canonical source `path` | `OUTGOING` |

All relation enums are case-sensitive uppercase; `direction` is `INCOMING` or
`OUTGOING`, never lowercase. Optional `relationKinds` and `resolutionStates` are
enum lists: omitted/empty means all, duplicates normalize, explicit null is invalid.
The filter field is `relationKinds`; do not reuse `search_code`'s `kinds` field.
`directory` filters occurrence paths, not targets. `limit` defaults to 20, maximum
100. Unknown fields and anchor/direction mismatches fail `INVALID_ARGUMENT`.
Correct malformed arguments against the schema while retaining exact context and
generation; do not blindly repeat an unchanged failure, remove pins or prepare a
replacement. Example only; replace every identity with values from the same context:

```json
{"context":{"repositoryId":"sample","revision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},"indexGeneration":"00000000-0000-0000-0000-000000000001","anchor":{"kind":"FILE","path":"src/main/java/Order.java"},"direction":"OUTGOING","relationKinds":["CALL","CONSTRUCT","METHOD_REFERENCE"],"limit":20}
```

Kinds are `CALL`, `CONSTRUCT`, `METHOD_REFERENCE`, `EXTENDS`, `IMPLEMENTS`,
`OVERRIDES`, `REFERENCE`, `HTTP_EVIDENCE`, `EVENT_EVIDENCE`, `DB_EVIDENCE`.
Results carry `analysisLevel: STATIC`, exact source/index identity, capabilities,
syntax and relation coverage, resolution provenance, whole facts and page metadata.
`STATIC_RESOLVED` identifies a static written declaration or approved external
signature, not runtime dispatch. `UNRESOLVED` has no target and a safe reason.
Lambdas, initializers and anonymous methods have distinct execution scopes;
navigation containers do not imply direct execution by the enclosing method.
`executionScope.kind: CONSTRUCTOR` includes written compact record constructors;
their body calls remain associated with the constructor's SOURCE_SYMBOL anchor.
When present, a navigation container is the nearest published ancestor declaration
covering both the occurrence and its execution scope. A field-type reference
whose scope is the enclosing type therefore uses that type, not the narrower
field declaration. Use a FILE anchor to include facts across its containers.
Incoming matches recorded identities, never unresolved names or inferred joins.
Outgoing is not a recursive traversal of nested declarations/callees.

FILE responses disclose the normal file outcome and Relations status (`ANALYZED`,
`OUT_OF_SCOPE`, `PARSE_FAILED`, `UNSUPPORTED`, `TOO_LARGE`), including empty results.
Call coverage counts distinct occurrences, not repeated candidate associations.
`coverage` and `relationCoverage` describe the whole generation, not the current
anchor/filter. Count matched CALL rows only after reading every result page to
`hasMore=false`; a partial page sequence is not a complete caller/callee list.
At most 200,000 relation facts may publish; overflow fails rather than truncates.
Empty results or complete syntactic coverage cannot establish runtime absence.
Always confirm business claims with exact-context raw `read_source` evidence.

Framework facts are **always `CANDIDATE`**, even with statically resolved APIs:

| Family | Recognized written evidence | Not inferred |
| --- | --- | --- |
| Spring MVC / OpenFeign | Request/Get/Post/Put/Delete/PatchMapping; FeignClient | Combined runtime routes, client-to-server joins |
| Kafka / Rabbit | KafkaListener, RabbitListener; typed KafkaTemplate.send / RabbitTemplate.convertAndSend | Producer/consumer joins, resolved placeholders, topicPattern as a literal channel |
| JDBC | Connection.prepareStatement/prepareCall and Statement SQL methods | Runtime SQL/table lineage or DB connectivity |
| JPA / Spring Data / MyBatis | javax/jakarta Entity/Table, Spring Data Query, MyBatis Select/Insert/Update/Delete | Entity name as table name or evaluated query expressions |

Recognition basis is `STATIC_API`, `FULLY_QUALIFIED`, `DIRECT_IMPORT` or
`AMBIGUOUS_SIMPLE`, with raw supporting type/import evidence. Bare `send/query/save`
names do not prove a framework API. Argument roles require a verified overload;
unknown overloads retain unresolved role names rather than guessing by arity.
Only written literals are values: expressions are not constant-folded and
`${...}` / `#{...}` remain written text with binding uncertainty.

Continue with unchanged context, generation, anchor, direction, filters and limit,
copying `page.nextCursor`. Ordering is BINARY occurrence path, start byte, kind,
relation ID; Query revalidates actual previous-row membership. These cursors are
continuation data, not authorization or `read_source` authenticated bookmarks.
Source admission precedes capability checking. An exact expired generation fails,
never switches to selected/current. A valid legacy generation without Relations
returns `INDEX_CAPABILITY_UNAVAILABLE`; missing/corrupt capable projection returns
`INDEX_UNAVAILABLE`. Response/deadline/shared-permit limits above apply unchanged.

## Release and recovery

A source job recovers to `COMPLETE` on Indexer restart only when its original
repository/request/job identity, origin and pinned SHA validate together with
`state.preparation=COMPLETE` for that exact job ID, matching source membership,
and a verified receipt/context/manifest digest. A current pointer, old
membership, source directory or first-publication job ID alone is not proof that
this particular job committed. An uncommitted interrupted source `RUNNING` job
fails under the original request; it is not replayed, adopted or used to clear
an existing READY publication.

An old committed semantic receipt likewise recovers to `COMPLETE` even when its
SHA has since become historical; current-only admission applies to new semantic
work only. Uncommitted interrupted semantic `RUNNING` fails without automatic
replay. Private durable terminal jobs are the status-publication outbox: failed
public preparation-state writes are retried by the dispatcher and the latest
per-repository terminal status is reconciled on restart, without replacing a
newer preparation status. The latest private job is determined by an atomically
reserved monotonic per-repository admission sequence and private request-linked
job envelope, not wall-clock `acceptedAt`; an unused reservation leaves a harmless
gap, while missing/corrupt private ordering refuses recovery rather than guessing.
This is a new private admin-root format: do not point this release at prior
unwrapped private job records or expect a legacy decoder; use a new service-owned
namespace, not an old data migration. Published source format and immutable
revision receipts remain version 1. `get_job` by the original request ID is
authoritative while public status has not converged; do not submit a new request
to repair stale public status. A failed or disk-full default-branch refresh leaves
the previous READY source and current unchanged. Successful replacement starts
the old non-current revision's policy-2 retirement window; only eligible
non-current revisions may later be withdrawn by GC. Readership remains
independent of Indexer/remote availability while source membership remains.

For an index rollout/rebuild, first confirm the selected release has the required
[acceptance evidence](testing.md); local native results alone are insufficient.
Preserve policy-2 source roots and receipts unchanged. Provision the separate
index child and process-private JNI temp storage/permissions; deploy matching
writer/reader releases with separate credentials and the two Query read-only
mounts. Readers support known profiles 1/1, 2/2 and 3/3. Existing
historical generations remain readable under their compatible profile; do not
rebuild a non-current revision. Prepare missing historical source only when an
exact source read is needed; without a retained historical index, use source
tools rather than attempting a historical rebuild. New semantic jobs are only
for an exact SHA that is current at admission. Read and verify a committed
generation before relying on its syntax navigation.

For an incompatible index schema/profile, keep old artifacts for matching rollback.
Rebuild only when the exact source SHA is current, using a compatible release and
explicit new UUID; no historical generation is migrated, rewritten or silently
reselected. If a fresh index namespace is needed, configure the same logical
namespace on writer and reader without moving source.
Rollback must use matching index readers/writers (or a supported source-only
deployment); old source jobs retain their own format and cannot be converted by
editing JSON. A source-only rollback must not open or mutate semantic job/index
storage. No rebuild/rollback permits deletion of unrelated or still-read storage.
Wait for read guards and durable source/companion cleanup before reclamation;
never clear GC intents to make space.

