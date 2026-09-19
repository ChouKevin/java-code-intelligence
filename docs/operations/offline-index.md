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

`ensure`, `sync`, `checkout`, `rebuild`, `rollback`, and Git-evidence preparation only store an asynchronous job. Admission resolves the requested Git ref to an exact reachable SHA, but checkout and JDT LS start only when the job runs.

One Indexer process contains one `index-job-dispatcher` thread. It polls with `semantic.index-jobs.poll-delay` (default `1s`), starts the oldest `ACCEPTED` job, and runs it synchronously. There is no distributed-ownership protocol or automatic retry.

The dispatcher writes one of these stable failure categories:

- `SOURCE_UNAVAILABLE`: Git resolution, fetch, or exact checkout failed.
- `SCHEMA_REBUILD_REQUIRED`: the stored schema/index contract is incompatible.
- `PUBLICATION_CONFLICT`: the expected repository pointer changed before publication.
- `VALIDATION_FAILED`: the generated data did not pass validation.
- `WORKER_INTERRUPTED`: an unexpected failure, shutdown interruption, or incomplete prior run.

Submit a new job after correcting a failure. Do not edit a terminal job. Rebuilding the same revision creates and validates a new immutable generation before changing the pointer. Rollback points to an existing sealed generation and does not edit it.

## Startup and shutdown

Startup recovery runs before normal polling. It marks a leftover `RUNNING` job `COMPLETE` when the exact target was already published; every other leftover `RUNNING` job becomes `FAILED/WORKER_INTERRUPTED`. It does not retry either case.

Normal shutdown stops new polls and interrupts dispatcher work. If interruption occurs before publication, startup closes the job as interrupted. If publication completed before the process stopped, startup reconciles the stored publication intent to `COMPLETE`.

Git evidence uses the same recovery lane. A `GIT_REFS`, `GIT_HISTORY`, or `GIT_COMPARISON` job is reconciled to COMPLETE only when its repository-scoped immutable catalog, history, or comparison manifest is already READY; otherwise startup records `FAILED/WORKER_INTERRUPTED`. A READY comparison retains immutable previous/current snapshots for historical file, diff, and search reads. Query reads no PREPARING or FAILED rows, and evidence preparation never changes a semantic repository pointer.

## Schema version 3 and retention

Schema bootstrap is a separate maintenance action. Indexer does not create or repair
named indexes while running a job. Schema 3 does not decode schema-2 generations:
there is no compatibility reader, default, or handwritten migration.

For a persisted-contract change:

1. Drain new admissions, stop the old Indexer, and settle active jobs through their
   normal terminal recovery before maintenance.
2. Back up repository pointers, jobs, manifests and payloads, Git evidence, and the
   whole review-reference graph coherently.
3. Deploy and verify schema-3 bootstrap with the maintenance identity.
4. Deploy runtime Indexer with its Mongo writer identity.
5. Rebuild each approved current generation and verify sealed schema-3 analysis
   evidence; reprepare standalone Git evidence as needed.
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
online. A stale current semantic revision returns `REVISION_OUTDATED` with the
current revision so the caller can retry with it; a review side keeps its exact
READY revision and rejects a mismatch. Historical Git evidence keeps its returned
immutable evidence ID and exact SHA; review-owned evidence also requires its owning
review to remain READY.

## UAT-only controls

The `uat` Spring profile adds:

- a one-cycle pre-publication gate at `/index/uat/publication/{arm,await,release}`;
- a repository reset admission endpoint at `/index/uat/repositories/{repoId}/reset`.

Both use the Indexer admin token. The gate is in-memory and only pauses the final pointer change. Reset is an ordinary durable job in the same dispatcher lane. Reset checks that the repository is configured, the database is exactly `semantic_uat`, and repository/workspace paths stay under their configured roots. It removes that repository's previous jobs, generations, pointer, checkout, and JDT workspace state but preserves the active reset job, other repositories, and shared content-addressed `source_artifacts`.

Never enable the UAT profile against production data.
