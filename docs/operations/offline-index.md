# Offline Index Operations

## Service boundary

Run Indexer and Query as separate applications with separate credentials. Indexer gets the admin token, read-only Git access, JDT LS workspace storage, and Mongo generation/job/pointer writes. Query gets only the query token and Mongo read access to repository pointers and sealed generations. It must not mount repositories or JDT workspaces.

Use TLS for a non-UAT MongoDB deployment, validate the server CA, and keep credentials in the deployment secret store. The publication path uses standalone MongoDB compare-and-set writes; it does not need transactions or a replica set.

## Jobs and the single dispatcher

`ensure`, `sync`, `checkout`, `rebuild`, and `rollback` only store an asynchronous job. Admission resolves the requested Git ref to an exact reachable SHA, but checkout and JDT LS start only when the job runs.

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

## Schema version 2

Schema bootstrap is a separate maintenance action. Indexer does not create or repair named indexes while running a job. Schema version 2 removed the old distributed ownership fields and has no backward reader. Rebuild a pre-version-2 UAT database; do not add compatibility codecs or handwritten migrations.

For a projection change:

1. Deploy and verify the schema bootstrap with the maintenance identity.
2. Deploy Indexer.
3. Rebuild each affected repository and verify its sealed manifest.
4. Deploy Query only after all required current generations use the new projection.

Back up pointer, job, manifest, and generation collections together. There is no automatic generation garbage collection; deletion needs a separate approved retention procedure.

## Query operation

Query reads MongoDB only. It must continue serving the repository catalog, search, source, type/member, route, reference, implementation, and call-graph tool families while Indexer is stopped. It never starts JDT LS or repairs missing data online. A stale requested revision returns `REVISION_OUTDATED` with the current revision so the caller can decide whether to query again.

## UAT-only controls

The `uat` Spring profile adds:

- a one-cycle pre-publication gate at `/index/uat/publication/{arm,await,release}`;
- a repository reset admission endpoint at `/index/uat/repositories/{repoId}/reset`.

Both use the Indexer admin token. The gate is in-memory and only pauses the final pointer change. Reset is an ordinary durable job in the same dispatcher lane. Reset checks that the repository is configured, the database is exactly `semantic_uat`, and repository/workspace paths stay under their configured roots. It removes that repository's previous jobs, generations, pointer, checkout, and JDT workspace state but preserves the active reset job, other repositories, and shared content-addressed `source_artifacts`.

Never enable the UAT profile against production data.
