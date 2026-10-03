# Semantic review deployment and operation

This guide operates the schema-4 commit/range semantic-review release. It
uses the existing Indexer, Query, and MongoDB processes; it does not add a
review service, model, prompt runtime, chat history, or findings store. An
external client such as OMP interprets evidence returned by Query.

## Startup and repository onboarding

Use this order for the first deployment or the first approved repository.
Repository registration, semantic indexing, and optional guide authoring are
different operations. There is no automatic indexing or summarization at startup.

1. **Provision the runtime.** Use Java 21, MongoDB, and the production Indexer
   image with its restricted JDT LS analysis child. Follow
   [topology and storage](#single-vm-topology-and-trust-boundaries) and
   [credentials and configuration](#credentials-mongo-roles-and-configuration).
   Prepare distinct maintenance, Indexer writer, and Query reader Mongo identities,
   separate API tokens, read-only Git credentials, private networking and approved
   Query ingress. Do not give Query checkout/JDT mounts or an Indexer endpoint.
2. **Register the repository.** Configure Indexer's `semantic.repositories` entry
   with a stable repository ID, Git URL and fixed `default-branch`; optionally set
   `project-guide-path`. Explicitly approve the same ID in Query's
   `semantic.query.git-evidence.allowed-repositories` before source/patch reading.
   Apply configuration through the deployment's normal restart/release procedure;
   `prepare_codebase` does not register a URL or change the configured branch.
3. **Optionally author a project guide.** On an independent approved clone, use the
   [shared external-agent prompt](repository-context-prompt.md). Limit reading to
   the authorized source scope, review references and sensitive content, and commit
   the reviewed guide through the target repository's normal PR process to the
   fixed branch. Match its path to `project-guide-path`. Do not use an
   Indexer-managed checkout. Skip this step when no guide is needed: basic code
   indexing does not depend on a summary, model or guide.
4. **Bootstrap, then start.** Build from the reactor root if using local artifacts:

   ```bash
   mvn --batch-mode --no-transfer-progress -DskipTests package
   docker build -f Dockerfile.indexer -t java-semantic-indexer:uat .
   docker build -f Dockerfile.query -t java-semantic-query:uat .
   ```

   Run the documented schema-bootstrap command with the **maintenance** Mongo URI,
   then start Indexer with its **writer** URI and Query with its **reader** URI.
   Runtime configuration and schema bootstrap are described
   [below](#credentials-mongo-roles-and-configuration); the Indexer image launch and
   ownership prerequisites are in [topology](#single-vm-topology-and-trust-boundaries).
   Choose explicit private bind addresses/ports. Schema 4 is a clean cutover;
   upgrading older persisted data requires the
   [release/rebuild sequence](tool-data-evolution.md), not merely restarting.
5. **Connect the MCP client.** A preparation-capable client needs two server
   entries: private Indexer `/mcp` with `X-Api-Token` from
   `SEMANTIC_INDEXER_ADMIN_TOKEN`, and Query `/mcp` with the separate
   `SEMANTIC_QUERY_API_TOKEN`. Use URLs reachable from that client, not a container's
   loopback address. Tool discovery should show preparation/status tools on Indexer
   and evidence tools on Query. Evidence-only clients need Query alone. Never put
   populated credentials into committed client configuration.
6. **Prepare the first codebase.** Discover `list_repositories` and
   `get_context` with `{"repositoryId":"orders","selector":{"kind":"CURRENT"}}`.
   A newly registered repository is UNINDEXED. Generate and durably save a canonical
   lowercase UUID, then call Indexer:

   ```json
   {
     "repositoryId": "orders",
     "requestId": "<saved-canonical-lowercase-uuid>"
   }
   ```

   Pass that object to `prepare_codebase`; do not add branch/revision overrides.
   Use `get_job` with the same `repositoryId` and saved `requestId` until terminal.
   ACCEPTED is not READY. An unknown submission outcome requires original-intent
   lookup; a failed job requires inspection before an explicit new intent.
7. **Read only published evidence.** After COMPLETE, rediscover CURRENT READY and
   copy the returned exact context into `get_outline`, `read_source` and
   `find_relations`. Inspect coverage/omission fields; unresolved or empty relations
   do not prove dead code. If a guide is AVAILABLE, read it as PROJECT_GUIDE and
   retain its `NOT_VERIFIED` freshness. To review a historical change, use
   [separate review preparation](#current-generations-and-review-preparation);
   do not replace current with the review commit.

For later source or guide commits, submit a new saved codebase preparation intent
against the configured branch. Guide generation is not repeated by the service;
guide freshness is not automatically certified. Client-specific acceptance and
observed verification limits are in [MCP Agent Acceptance](mcp-agent-acceptance.md).

## Single-VM topology and trust boundaries

Run three processes on the VM:

| Process | Persistent storage and authority | Network exposure |
| --- | --- | --- |
| MongoDB | `/srv/semantic/mongo`; schema, jobs, pointers, sealed generations, review manifests, and Git evidence | private network only |
| Indexer | `/srv/semantic/indexer/checkouts` and `/srv/semantic/indexer/jdtls`; read-only Git and Mongo writer identity | private admin network only |
| Query | no checkout, source, or JDT workspace mount; Mongo reader identity only | the approved Query ingress |

Indexer owns every checkout, JDT LS process, analysis, validation, and
publication. Run exactly one Indexer process with its one
`index-job-dispatcher`; it serializes the oldest accepted job and allows one
active job per repository. Query is Mongo-only: it neither mounts nor receives
an Indexer URL, Git credential, `JDTLS_HOME`, repository checkout, source tree,
or JDT workspace, and it never starts or controls Indexer.

The mounted checkout is an Indexer-managed clone, not an operator clone. Make
Git credentials read-only and mount them only for Indexer. Treat a PR checkout
and its import as untrusted input: the configured analysis child runs with the
restricted `analysis` UID in the published Indexer image and must not receive
unrelated VM secrets.

Build images from the reactor root with the committed `.dockerignore`. It keeps
Git/editor state, private `.superpowers` scratch data, nested worktrees, and Maven
`target` directories out of the build context, so container builds compile source
rather than importing host build outputs.

For the published Indexer image, mount the two declared durable paths separately:
`/data/repos` for the disposable managed checkout and `/data/jdtls` for JDT LS
lease data. Do not mount only their `/data` parent: the image declares each
child as a volume, so Docker otherwise creates anonymous child volumes that
hide the parent mount.

After provisioning the private Docker network, secret-backed Indexer env file,
checkout storage for the Indexer, analysis-owned JDT workspace storage, and the
image tagged `java-semantic-indexer:uat`, start it with both child paths bound explicitly:

```bash
docker run -d --name semantic-indexer --network semantic-private \
  --env-file /etc/semantic/indexer.env \
  --mount type=bind,source=/srv/semantic/indexer/checkouts,target=/data/repos \
  --mount type=bind,source=/srv/semantic/indexer/jdtls,target=/data/jdtls \
  java-semantic-indexer:uat
```

The container's `SEMANTIC_DATA_ROOT=/data/repos` and
`JDTLS_WORKSPACE_DATA_ROOT=/data/jdtls` refer to these exact targets; neither
host directory is mounted into Query.

For the `LINUX_UID` image, `/data` and `/data/repos` remain application-owned
and the analysis UID cannot write them; `/data/jdtls` alone is analysis-owned.
Do not recursively transfer `/data` or `/data/repos` to `analysis`. Before JDT
LS starts, every canonical directory from `/` through the checkout root must
have an authority chain that prevents analysis from replacing the next entry.
Fresh checkout roots are application-owned, owned by the configured analysis
group, and use sticky group-writable/traversable mode `01770`. This permits
ordinary Maven project metadata creation while the sticky bit protects the
application-owned `.git` entry. `.git` and all authoritative control entries
remain application-owned and analysis-nonwritable. Only ordinary tracked
worktree entries (including symlinks) are made analysis-owned; symlink targets
are not followed. The image smoke script proves UID 10001 can create project
metadata and edit tracked source content while it cannot replace `.git`,
modify or rename its controls, or replace the checkout or any ancestor.

Clone/fetch keep JGit's automatic object maintenance synchronous with the
owning operation, rather than leaving background GC to alter `.git` after
validation or close. Fresh clones persist `gc.autoDetach=false`; fetches also
override the setting in memory. GC time counts against the single dispatcher
operation. Do not run external Git maintenance while Indexer owns the checkout.

Imported source-root classification uses project-relative paths even when JDT
returns absolute paths. Readiness filters source files relative to each admitted
root; repository or ancestor names containing `generated`, `build`, or `test`
must not exclude ordinary production sources. Generated/build/test subtrees,
source-root containment, and exact declaration/URI evidence remain enforced.

Mongo and Indexer admin must bind only to the private management interface or
private container network. Do not publish MongoDB, `/index/**`, or the Indexer's
`/mcp` on a public address. Query's separate HTTP/MCP service may be exposed only
through approved ingress. For a direct Spring Boot TLS deployment, mount private
certificate files and set:

```bash
SERVER_SSL_ENABLED=true
SERVER_SSL_CERTIFICATE=file:/run/secrets/query.crt
SERVER_SSL_CERTIFICATE_PRIVATE_KEY=file:/run/secrets/query.key
```

At a real deployment, smoke the configured Query hostname and validate the
presented certificate/hostname. TLS is not demonstrated by a localhost HTTP
journey; an existing approved TLS ingress is an alternative to direct Boot TLS.

## Credentials, Mongo roles, and configuration

Use three Mongo identities in the `semantic` database:

- `semantic-schema-maintenance` creates/verifies the schema/index catalogue and
  is used only for bootstrap;
- `semantic-index-writer` has data find/insert/update/remove and
  listCollections/listIndexes, not schema DDL;
- `semantic-query-reader` has only the reads Query needs.

Create these identities through the VM's approved secret/DB provisioning path;
do not put passwords in command history, examples, images, or this repository.
The roles must be least-privilege equivalents of schema-maintenance DDL, Indexer
collection writes, and Query collection reads. Do not give Query write,
`dbAdmin`, Git, or JDT permissions. Use separate `X-Api-Token` credentials:
`SEMANTIC_INDEXER_ADMIN_TOKEN` for Indexer `/index/**` and `/mcp`, and
`SEMANTIC_QUERY_API_TOKEN` for Query HTTP and its own `/mcp`.

The following shows separate container env files, with secret-file references
rather than secret values. Substitute the secret manager's file-loading
mechanism before launching the images:

```bash
# /etc/semantic/indexer.env: readable only by the Indexer service account
SEMANTIC_MONGODB_URI='mongodb://semantic-index-writer:<from-secret-file>@mongo.private/semantic?tls=true'
SEMANTIC_INDEXER_ADMIN_TOKEN='<from-/run/secrets/indexer-admin-token>'
GIT_USERNAME='<from-/run/secrets/git-username>'
GIT_TOKEN='<from-/run/secrets/git-read-token>'
SEMANTIC_DATA_ROOT=/data/repos
JDTLS_HOME=/opt/jdtls
JDTLS_WORKSPACE_DATA_ROOT=/data/jdtls
JDTLS_ENABLED=true
JDTLS_ISOLATION_MODE=LINUX_UID
JDTLS_ANALYSIS_UID=10001
JDTLS_ANALYSIS_GID=10001
JDTLS_ANALYSIS_HOME=/home/analysis

# /etc/semantic/query.env: readable only by the Query service account
SEMANTIC_MONGODB_URI='mongodb://semantic-query-reader:<from-secret-file>@mongo.private/semantic?tls=true'
SEMANTIC_QUERY_API_TOKEN='<from-/run/secrets/query-read-token>'
```

For a host-jar Indexer instead of the image, use the host paths explicitly
in its Indexer env file:

```bash
SEMANTIC_DATA_ROOT=/srv/semantic/indexer/checkouts
JDTLS_WORKSPACE_DATA_ROOT=/srv/semantic/indexer/jdtls
```

LINUX_UID also requires actual cross-UID filesystem ownership and child-process
privileges. An unprivileged host JVM with a JDT directory is insufficient.
LOCAL_TRUSTED is an explicit trusted-fixture mode, not production UID isolation.

Register each approved repository in Indexer configuration. `url` is the
read-only Git URL and `default-branch` supplies ordinary admission; a local
operator clone is never the configured checkout:

```yaml
semantic:
  repositories:
    orders:
      url: https://git.example.invalid/team/orders.git
      default-branch: main
      # Optional exact, reviewed Markdown exception:
      project-guide-path: docs/codebase/overview.md
```

Keep Query's source-evidence gate empty until an administrator approves a whole
repository. Then configure the exact same repository IDs explicitly:

```yaml
semantic:
  query:
    git-evidence:
      allowed-repositories: [orders]
```

Repository allowlisting does not bypass the fail-closed forbidden repository,
package, class, method, source, or fact policies. `SEMANTIC_API_TOKEN` is a
legacy **acceptance-client** variable (for example, `-Pdeployed-it`); it is not
the Query server binding. The server reads `SEMANTIC_QUERY_API_TOKEN`.

Pre-create `SEMANTIC_DATA_ROOT` as a real canonical directory owned by Indexer.
The managed checkout boundary rejects symbolic-link ancestors and does not
silently relax that protection for a first metadata refresh.

Start schema bootstrap only with the maintenance Mongo URI, before either
runtime application:

```bash
java -jar semantic-indexer/target/semantic-indexer-0.0.1-SNAPSHOT.jar \
  --semantic.schema-bootstrap=true
```

Start the runtime Indexer with its writer URI and Query with its reader URI.
The schema-bootstrap process is intentionally minimal and is not an Indexer
worker. The default ports are Spring Boot's `8080`; set an explicit private
`server.address`/`server.port` in deployment configuration rather than relying
on public defaults.

Startup publishes configured registry metadata only; it does not index repositories.
Connect the external client to two independently credentialed MCP entries: private
Indexer for preparation/status, Query for evidence. Discover `UNINDEXED`, save a
canonical requestId, call `prepare_codebase` for the configured branch, recover/poll
that identity, then rediscover READY and exercise outline/source/relations.
Metadata readiness is not codebase readiness. A later active BUILD is separate
from the old published pointer; failure preserves the old publication.

The optional [external guide prompt](repository-context-prompt.md) runs on an
independent approved clone, not inside either application. Review and commit its
output to the fixed branch before preparing a new current. Missing/invalid guides
do not block code. Query marks readable guides PROJECT_GUIDE, retains author
analyzedRevision separately from importedRevision/digest, and reports NOT_VERIFIED
freshness. Guide text is not semantic/text-search evidence, and historical sides
never borrow a current document.

Size Indexer/JDT, Query, and Mongo separately. The JDT LS spike observed about
1 GiB RSS per trivial workspace (1,030,328 KiB at `-Xmx768m` and 1,045,700 KiB
at `-Xmx2g`); its Equinox/OSGi/JDK baseline means heap flags are not a container
size. With two active workspaces the observed floor is about 2 GiB RSS before
Indexer overhead. Heap adequacy was not measured. Use
`jdtls.workspace.peak.rss.kilobytes{repository}` from a real import and leave
headroom for Indexer, Query, Mongo, and the VM. This is not a 50-user throughput
or capacity claim.

## Current generations and review preparation

Discover visible repositories with `list_repositories`, then call `get_context`
with `selector: {"kind":"CURRENT"}`. Copy its READY `context` into the unified
navigation tools. Exact current SHA is mandatory; `REVISION_OUTDATED` requires
rediscovery and revision-scoped fact IDs before a fact-bound retry. Structural
overview and omitted/coverage fields describe indexed evidence, not business
completeness.

A review is separate immutable READY membership. Before submitting, the client
creates and durably saves a canonical lowercase UUID `requestId`. Choose one
explicit selection for `POST /index/repositories/{repositoryId}/reviews`
(or the Indexer's `prepare_review` tool):

- `{"requestId":"<saved-client-uuid>","selection":{"kind":"COMMIT","revision":"<full-lowercase-sha>"}}`
  compares the first parent to the requested commit. For a root commit it
  compares the real Git empty tree to that commit; there is no before semantic
  generation and `before.kind` is `EMPTY_TREE`.
- `{"requestId":"<saved-client-uuid>","selection":{"kind":"RANGE","beforeRevision":"<full-lowercase-sha>","afterRevision":"<full-lowercase-sha>"}}`
  compares precisely those two reachable commits in that order, including
  equal or divergent commits. It does not calculate a merge base.

Neither selection reads or captures the mutable current pointer at admission.
Both requested commits must be reachable from fetched trusted remote refs.
Do not admit the after commit through ordinary `/ensure`, `/sync`, `/checkout`,
or `/rebuild`: those are BUILD operations and may publish it as current.

Missing or noncanonical `requestId`, missing `selection`/`kind`, malformed full
SHA, unknown fields, and mixed or incomplete COMMIT/RANGE fields return HTTP
`400` with `code: INVALID_ARGUMENT`; no review job is admitted. HTTP and MCP use
the same strict application contract and `code`, `message`, `retryable` errors.
MCP supplies identical application JSON in structuredContent and TextContent;
authentication and malformed transport failures remain transport errors.

The private review admin request is:

```bash
curl --fail-with-body -sS -i \
  -H "X-Api-Token: $SEMANTIC_INDEXER_ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"<saved-client-uuid>","selection":{"kind":"COMMIT","revision":"<full-lowercase-sha>"}}' \
  https://indexer.private/index/repositories/orders/reviews
```

Require HTTP `202` with the original `requestId`, nonempty `jobId`,
`review.reviewId`, and exactly the requested `review.selection`. Follow the
returned `Location`, `/index/repositories/{repositoryId}/jobs?jobId=…`, and poll
until terminal `phase: COMPLETE` and `review.stage: READY`. The worker first
fetches and resolves the endpoints, persists `review.resolvedEndpoints` with
`baselineRule` (`FIRST_PARENT`, `EMPTY_TREE`, or `DIRECT_RANGE`), then prepares
available semantic sides and Git evidence. An accepted job can still be
`RESOLVING` and need not have reserved generation IDs yet. Review completion
records `comparisonId`, `previousSnapshotId`, and `currentSnapshotId`; the
root's previous snapshot is empty but real, without a fabricated revision.

For a root comparison, copy the returned `comparisonContext` unchanged into
`compare_revisions` and `get_file_diff`; its before endpoint explicitly has
`kind: EMPTY_TREE`. Read an ADD patch using the returned changeId. There is no
BEFORE semantic context, omitted-previous compatibility form or public raw-ID
override.

If the job is `operation: BUILD` or selection/review identity differs, stop
and investigate rather than interpreting it as review progress. An unrelated
current-pointer update does not alter the review's resolved endpoints or
READY membership. An interruption, lost response, or timeout means look up the
original `requestId` with
`GET /index/repositories/{repositoryId}/jobs?requestId=…` or `get_job`. Lookup
requires jobId XOR requestId and includes terminal jobs after later admissions
and process restart; it never picks the latest or active job.

`REQUEST_NOT_FOUND` means acceptance remains unknown, not proof that submission
failed. Continue looking up that UUID or stop waiting; do not silently resubmit.
Submitting a reused UUID returns `REQUEST_ID_REUSED` with its original job
identity, without Git resolution or new work. A retry after an inspected failure
requires explicit new intent and a new UUID. Terminal request identities have
no automatic TTL.

Give the external client the Query MCP endpoint, secure Query credential,
repositoryId and reviewId. Call `get_context` with
`selector: {"kind":"REVIEW","reviewId":"…"}`. COMMIT/RANGE selectors can also
discover the latest preparation state for that exact requested selection.
Discovery does not prepare anything; NOT_PREPARED/PREPARING/FAILED returns no
readable half-context.

Copy READY `comparisonContext` for direct comparison/patch calls and each present
`before.context`/`after.context` for the same seven navigation tools:
`search_code`, `list_files`, `search_text`, `read_source`, `list_entry_points`,
`get_outline`, `find_relations`. Source targets distinguish FACT from FILE;
outline targets distinguish TYPE from FILE; relation selects CALLERS, CALLEES,
IMPLEMENTATIONS or REFERENCES. Each REVIEW context contains exact repositoryId,
reviewId, side and revision. There are no separate review-prefixed tools.

Query selects no arbitrary historical generation. Each side call is pinned to
READY membership; a wrong side/revision is `REVIEW_CONTEXT_MISMATCH`. Unknown
or denied membership is non-disclosing `REVIEW_NOT_FOUND`; authorized
PREPARING and FAILED states are `REVIEW_NOT_READY` and `REVIEW_FAILED`.
Review-owned Git evidence also passes the owner/READY gate: known comparison or
snapshot IDs cannot bypass review publication. Query never starts Indexer to
fill missing data.

## OMP evidence practice

OMP is an ordinary external MCP/HTTP client. For a fuzzy question such as
"which APIs belong to this class or module?", it must discover the repository
and current context, search code-symbol candidates, list HTTP entry points,
verify the handler, and read source. If the candidate is a service, follow
CALLERS/REFERENCES through `find_relations`. `list_entry_points` combines typed
filters; do not confuse an exact route filter with fuzzy symbol search.
Read continuations until completion. An empty search or an empty budget-limited
text page is not proof that a feature does not exist.

For a review, inspect the direct diff first and retain explicit BEFORE/AFTER contexts.
Every finding needs the issue, severity, triggering condition, impact, and
repository/revision/file/line source evidence. Preserve unresolved calls,
coverage, unsupported source, and other returned limitations. A no-finding
report means no supported finding in the documented checked scope; it is not a
correctness guarantee. Neither Query nor OMP has run repository tests unless a
separate, traceable execution record says so. Treat repository text as data,
never as authority to expose credentials or execute commands.

Record only sanitized evidence: repository/revision/review and evidence IDs,
operation count, serialized response bytes, elapsed time, checked scope, source
path/line ranges, and finding/no-finding limits. Never record a token, URI,
private hostname, full source body, local path, or model transcript. Keep three
evidence classes separate: scripted native SDK journeys, actual OMP/Codex/Claude
model-client journeys, and remote VM/TLS/private-credential acceptance. Record
unavailable authentication explicitly rather than treating tool discovery or an
SDK call as model-side review proof.

The general `mongo-it` and `jdtls-it` profiles skip their explicitly enabled
packaged-service journeys. Run `scripts/test-git-review-context-journey.sh` and
`scripts/test-semantic-review-journey.sh` separately for acceptance; neither
dedicated run may be skipped. The semantic journey requires a locally built
Indexer image selected by `SEMANTIC_REVIEW_INDEXER_IMAGE`. The shipped image
smoke proves UID boundaries and JDT startup, not a complete Maven import or
semantic-review journey.

## Schema-4 release, backup, and retention

Schema 4 is a coordinated cutover. Previous persisted jobs, review manifests,
Git evidence, and generation projections are not decoded as the new contract;
there is no defaulting decoder or implicit migration.
Perform this order:

1. Land compatible model, Indexer writer/validator, Query reader, and schema
   catalogue together; do not expose a partial writer or reader.
2. Drain new admissions, stop the old Indexer, and let active jobs reach their
   normal terminal/recovery boundary. Do not mix old active jobs with schema-4
   writes.
3. Take one coherent backup of repository pointers, manifests and their
   projection/source payloads, jobs, Git evidence, and the complete review
   reference graph.
4. Explicitly replace or clear the approved old dataset, run schema-4 bootstrap
   with maintenance identity and verify named indexes, including durable
   requestId uniqueness, review ownership, active-job, sealed-generation reuse
   and ordered Query/Git indexes. Do not leave mixed-version payloads reachable.
5. Run the schema-4 Indexer with the writer identity. Rebuild every approved
   repository's current generation so its projections and semantic analysis
   evidence are compatible.
6. Refresh approved branch metadata and create needed COMMIT/RANGE reviews from
   fetched reachable commits; no published current generation is required.
7. Verify manifests/evidence/indexes, deploy Query, then reopen admissions.

A same-SHA rebuild writes a new sealed generation before changing current; a
READY review remains pinned to its selected generation/digest. Retain every
READY review as a graph: review manifest, the after generation, optional before
generation, comparison, and both snapshots. Current updates do not make these artifacts deletable.

There is no TTL, automatic garbage collection, source-snapshot deduplication,
or unique content-SHA rule. Every eligible comparison writes its own two
snapshots and duplicates eligible snapshot text even when a semantic generation
is reused. Manual maintenance first inventories active jobs and every review
reference, then takes/validates a coherent backup and removes only unreferenced
artifacts outside active work. Do not race cleanup against the dispatcher or
remove one member of a retained review graph.

## Fail-closed managed checkout upgrade and recovery

When a `LINUX_UID` ownership check rejects an existing checkout after an
upgrade, leave it rejected. Do not chmod, chown, relink, or otherwise repair or
reclaim `.git` in place. Use this recovery sequence:

1. Drain new Indexer admissions and let active work reach a terminal boundary.
2. Stop the Indexer and verify it has exited and no analysis-UID child process
   remains. Do not remove a checkout while Indexer or an analysis child can use
   it.
3. Provision a trusted, canonical managed-parent chain that is application-owned
   and cannot be replaced by the analysis UID. Identify only the affected
   repository's disposable managed checkout from its runtime configuration.
   Using the application/operator identity that owns the managed parent, verify
   the parent is a real directory and the checkout is its direct child; inspect
   for nested mounts before recursive removal. If the checkout entry itself is a
   symlink, unlink that entry only. If it is a real directory, remove only that
   disposable directory with a no-follow removal operation. Never resolve a
   checkout symlink and remove its target, traverse symlinks, glob across
   repositories, or remove the managed parent.
4. Preserve MongoDB volumes, repository pointers, generation/source evidence,
   Git evidence, READY review graphs, and coherent backups. Checkout recovery
   does not require deleting or rewriting MongoDB or review data.
5. Restart the Indexer only after the authority chain is safe. Re-admit the
   repository through normal Indexer operations so it reclones from the
   configured `RepositoryRuntime.remoteUrl`; do not substitute an operator
   clone or an unconfigured origin.

An unsafe persisted checkout is disposable; its `.git` ownership or modes are
not an operator repair target. Correct storage-parent authority separately,
then let Indexer create a fresh managed checkout from the configured remote.

See [Offline Index Operations](offline-index.md), [Tool projection data
evolution](tool-data-evolution.md), [Git review context](git-review-context.md),
and [MCP Agent Acceptance](mcp-agent-acceptance.md) for the route-level and
local-acceptance details.
