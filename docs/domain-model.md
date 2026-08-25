# Offline Semantic Index Domain Model

## Deployment boundary

`Indexer` owns repository checkout, JDT/JDT-LS analysis, job claims, immutable generation writes, validation, sealing, and pointer publication. `Query` owns only authorization and read projection from MongoDB. It has no working tree, repository cache, JGit, JDT, JDT-LS, or LSP dependency.

## Generation lifecycle

An `IndexJob` selects one `RepositoryRevision` and builds one immutable `Generation`. The worker periodically renews its claim and fence while work is active. Validation precedes sealing; only a sealed generation may be published. Publication is a fenced, single-document update of the repository pointer, so it works on standalone MongoDB without transactions.

Rebuilding the same revision creates a new generation and never mutates the prior one. Rollback repoints the pointer to a previously sealed generation. Query returns data only from the pointer's sealed generation and rejects a stale requested revision.

## Persisted facts

The exporter projects source symbols, entry points, relations, route evidence, source content, and code facts. Every fact is keyed by repository, revision, and generation identity. The payment, order, and video UAT fixtures use this same exporter and publication path.

## Release ordering

Projection versions are forward-only. Additive fields and indexes may be deployed while the current Query understands the old meaning. A changed meaning requires a new projection field/index and a rebuild before Query reads it. There are no compatibility decoders or online fallbacks.
