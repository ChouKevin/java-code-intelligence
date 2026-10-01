# Offline Index Operations

## Service boundary

Run Indexer and Query as separate applications with separate credentials. Indexer gets
the admin token, read-only Git access, JDT LS workspace storage, and Mongo
generation/job/pointer writes. Query gets only the query token and Mongo read access
to repository pointers, sealed generations, READY review manifests, and READY Git
evidence. It must not mount repositories, source trees, or JDT workspaces, receive
Git/JDT credentials, or control Indexer.

Use TLS for a non-UAT MongoDB deployment, validate the server CA, and keep
credentials in the deployment secret store. The publication path uses standalone
MongoDB compare-and-set writes; it does not need transactions or a replica set.
Keep MongoDB and `/index/**` private. Query TLS uses Spring Boot's
`SERVER_SSL_ENABLED`, `SERVER_SSL_CERTIFICATE`, and
`SERVER_SSL_CERTIFICATE_PRIVATE_KEY` bindings or an approved TLS ingress; smoke the
configured hostname and certificate in the actual VM deployment.

## Jobs and the single dispatcher

The private Indexer MCP provides `prepare_codebase`, `refresh_repository_metadata`, `prepare_review`, and `get_job`, with equivalent HTTP operations. Each preparation requires a saved canonical lowercase UUID `requestId`. Codebase admission freshly pins only the configured branch; metadata refresh prepares a catalog/history pair without JDT; review resolves COMMIT first-parent or direct RANGE endpoints in the worker. Maintenance-only ensure/sync/checkout/rebuild/rollback remain administrator operations, not client compatibility aliases.

One Indexer process contains one `index-job-dispatcher` thread. It polls with `semantic.index-jobs.poll-delay` (default `1s`), starts the oldest `ACCEPTED` job, and runs it synchronously. There is no distributed-ownership protocol or automatic retry.

The dispatcher writes one of these stable failure categories:

- `SOURCE_UNAVAILABLE`: Git resolution, fetch, or exact checkout failed.
- `SCHEMA_REBUILD_REQUIRED`: the stored schema/index contract is incompatible.
- `PUBLICATION_CONFLICT`: the expected repository pointer changed before publication.
- `VALIDATION_FAILED`: the generated data did not pass validation.
- `WORKER_INTERRUPTED`: an unexpected failure, shutdown interruption, or incomplete prior run.
- `ANALYSIS_UNAVAILABLE`: required semantic analysis could not be prepared.
- `REVIEW_EVIDENCE_MISMATCH`: the prepared review graph does not match its required immutable evidence.

A lost response is not a failed job: use `get_job` with repositoryId plus exactly one of requestId/jobId to recover the original work, including after terminal completion, later jobs and restart. `REQUEST_NOT_FOUND` does not authorize resubmission; `REQUEST_ID_REUSED` admits nothing new. After an inspected failure and correction, submit an explicit new intent with a new UUID. Do not edit a terminal job. Rebuilding the same revision creates a new immutable generation before changing the pointer; rollback selects an existing sealed generation.

## Startup and shutdown

Startup recovery runs before normal polling. It marks a leftover `RUNNING` job `COMPLETE` when the exact target was already published; every other leftover `RUNNING` job becomes `FAILED/WORKER_INTERRUPTED`. It does not retry either case.

Normal shutdown stops new polls and interrupts dispatcher work. If interruption occurs before publication, startup closes the job as interrupted. If publication completed before the process stopped, startup reconciles the stored publication intent to `COMPLETE`.

Metadata recovery reconciles `GIT_METADATA` only when its exact READY catalog/history pair was published through the stored metadata pointer intent; an orphan READY pair is not publication. Review recovery validates the complete READY owner graph, including endpoint generations, snapshots, comparison and digest, before reconciling completion. It never resumes half-prepared work. Historical maintenance Git jobs remain in the same serialized lane. Query serves neither partial evidence nor a replacement current for an unavailable review.

## Schema version 4 and retention

Schema bootstrap is a separate maintenance action. Runtime Indexer does not create
or repair named indexes. Schema4/projections4, review2, Git3 and jobs3 form one
coordinated rebuild cutover; analysis evidence remains version1. Older data has
no compatibility decoder, default or handwritten migration.

For a persisted-contract change:

1. Drain new admissions, stop the old Indexer, and settle active jobs through their
   normal terminal recovery before maintenance.
2. Back up repository pointers, jobs, manifests and payloads, Git evidence, and the
   whole review-reference graph coherently.
3. Replace or clear only the explicitly approved old dataset, then run schema4
   bootstrap with the maintenance identity; never point new readers at mixed data.
4. Deploy runtime Indexer with its separate data-writer identity.
5. Rebuild approved current generations and metadata, then reprepare needed
   reviews; verify sealed source-policy/guide membership and analysis evidence.
6. Deploy Query only after compatible data is verified, then reopen admissions and
   prepare new reviews.

A same-SHA rebuild creates a new immutable generation before moving current. A READY
review remains pinned to its selected generation/digest and retains its manifest,
both generations, comparison, and snapshots as a graph. There is no TTL, automatic
garbage collection, unique content-SHA rule, or snapshot deduplication. Every
comparison duplicates eligible snapshot text independently of generation reuse.
Manual deletion must first exclude active jobs and all review references; do not race
maintenance with the dispatcher. See [Semantic review deployment and
operation](semantic-review.md) for the complete single-VM procedure.

## Query operation

Query reads MongoDB only. It serves current repository catalog, search, source,
type/member, route, reference, implementation, caller, and callee operations from
the current pointer, plus immutable A/B semantic review operations from separately
prepared READY review membership. It never starts JDT LS or repairs missing data
online. A stale CURRENT revision returns `REVISION_OUTDATED`; rediscover context
and fact IDs before retrying. A REVIEW context retains exact repositoryId,
reviewId, side and revision, independent of current movement. Copy the returned
comparisonContext for diff calls; raw generation/snapshot IDs do not bypass
membership. See the [thirteen unified operations](semantic-index-operations.md).

## Fresh-host preparation

1. Provision approved network/TLS, secret storage and an empty dedicated Mongo
   dataset. Bootstrap using a maintenance identity before either runtime starts.
2. Configure one Indexer process with fixed repository branches, read-only Git
   access, a data-only Mongo writer and private admin token. Start Query separately
   with a Mongo read identity and Query token; do not mount Git/JDT storage there.
3. Prefer the production Indexer image for LINUX_UID isolation. Its managed checkout
   parent is application-owned and not writable by the analysis UID; provision
   real canonical storage directories before admission. A host-jar deployment
   needs the actual cross-UID filesystem/process privileges, not merely Java and
   a JDT LS directory. LOCAL_TRUSTED is for explicitly trusted local fixtures,
   not an equivalent production isolation claim.
4. Register two client MCP entries with different tokens. Discover repositories
   and CURRENT context: configuration alone leaves the repository UNINDEXED.
5. Save requestId, submit prepare_codebase, recover/poll that identity to COMPLETE,
   then rediscover READY context and exercise outline/source/relations. An active
   job and metadata readiness are not current publication.
6. Optionally author the [project guide](repository-context-prompt.md) outside the
   service, review and commit it to the fixed branch, configure its exact Markdown
   path and prepare a new current. Missing/invalid guide content does not block code.
7. During later preparation keep reading the old published context until its SHA
   expires; failures retain it. Prepared review contexts remain pinned. Verify
   actual TLS/client behavior separately; a local SDK fixture is not that proof.

## UAT-only controls

The `uat` Spring profile adds:

- a one-cycle pre-publication gate at `/index/uat/publication/{arm,await,release}`;
- a repository reset admission endpoint at `/index/uat/repositories/{repoId}/reset`.

Both use the Indexer admin token. The gate is in-memory and only pauses the final pointer change. Reset is an ordinary durable job in the same dispatcher lane. Reset checks that the repository is configured, the database is exactly `semantic_uat`, and repository/workspace paths stay under their configured roots. It removes that repository's previous jobs, generations, pointer, checkout, and JDT workspace state but preserves the active reset job, other repositories, and shared content-addressed `source_artifacts`.

Never enable the UAT profile against production data.
