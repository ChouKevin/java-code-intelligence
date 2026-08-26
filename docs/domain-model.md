# Offline Semantic Index Domain Model

## Deployment boundary

`Indexer` owns Git ref resolution, exact checkout, JDT/JDT-LS analysis, durable index jobs, immutable generation writes, validation, sealing, and pointer publication. `Query` owns authorization and read projection from MongoDB. It has no working tree, repository cache, JGit, JDT, JDT LS, or LSP dependency.

## Job lifecycle

An `IndexJob` has one operation (`BUILD`, `ROLLBACK`, `RESET`, or terminal `NO_WORK`) and one phase (`ACCEPTED`, `RUNNING`, `COMPLETE`, or `FAILED`). Build and rollback jobs carry an exact target; reset does not invent one. One dispatcher starts the oldest accepted job and the executor owns its terminal transition.

Jobs store only the data needed by this single-process lane and have no automatic retry state. Failures use stable categories so an operator can correct the cause and submit a new job. Startup only reconciles an already-published target or closes unfinished running work as interrupted.

## Generation lifecycle

A build uses the exact admitted `RepositoryRevision` and creates one immutable `Generation`. Validation precedes sealing; only a sealed generation owned by the active job may be published. Publication changes the repository pointer with an expected-parent compare-and-set, so standalone MongoDB is sufficient.

Rebuilding the same revision creates a new generation and never mutates the prior generation. Rollback repoints to a previously sealed generation. Query returns data only from the current pointer's sealed generation and rejects a stale requested revision.

## Persisted facts

The exporter writes source artifacts, symbols, entry points, relations, route evidence, and code facts. Repository, revision, and generation identity keep each published view exact. Content-addressed source artifacts may be shared and are not removed by a repository-scoped UAT reset.

The payment, order, and video UAT fixtures use the same Git checkout, dispatcher, exporter, validation, seal, and publication path as other repositories. Only their gate and reset controls are UAT-specific.

## Release boundary

The current persisted contract is schema version 2. Projection changes are forward-only: bootstrap the schema, rebuild repositories, then deploy the matching Query. A changed meaning uses a new projection or a deliberate schema rebuild; there are no multi-version decoders or online source fallbacks.
