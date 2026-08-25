# Offline Index Operations

## Roles, credentials, and TLS

Run Indexer and Query with different service identities. The Indexer administrator token is accepted only by `/index/**`; the Query API token is accepted only by Query HTTP/MCP. Indexer receives a read-only Git credential and JDT-LS workspace storage. Query receives neither. Use separate Mongo roles: Indexer needs generation/job/pointer writes; Query needs read access to sealed generations and pointer documents only; schema maintenance has a third identity with index-management rights. Use TLS for MongoDB, validate the server CA, and keep credentials in the deployment secret store.

## Job behavior and recovery

Admin commands enqueue an asynchronous job for `ensure`, `sync`, `checkout`, `rebuild`, or `rollback`; no request performs inline indexing. A worker claims a job with a fence and periodically renews the claim. If renewal fails, it must stop before any further publication attempt. The build writes immutable generation documents, validates them, seals the manifest, and then performs the fenced single-document repository-pointer update. This publication model is safe on standalone MongoDB and does not require transactions or a replica set.

Retry a failed build by creating a **new job**. Do not revive or extend an expired claim. A rebuild of the same revision creates a new generation and moves the pointer only after the new generation is sealed. Rollback repoints to an existing sealed generation; it never edits generation documents.

## Release and schema procedure

1. Deploy additive schema/index maintenance with the schema-maintenance identity.
2. Deploy Indexer and rebuild affected repositories until their new generations are sealed.
3. Deploy Query only after the required projection version is present in current sealed generations.

Schema maintenance is non-destructive: add new fields or named indexes, and retain prior fields and indexes until no running Query release needs them. For rollback, repoint a repository to a sealed generation compatible with the previous Query, then roll back Query; reverse only additive schema changes in a separately reviewed maintenance operation. Do not use handwritten migration registries or multi-version decoders.

Back up MongoDB pointer, job, manifest, and generation collections together and periodically verify a restore into an isolated environment. There is currently **no automatic generation garbage collection**. Rebuilds accumulate immutable generations; operators must provision storage and use a separate, approved retention procedure before deleting any generation.

## Operational verification

Run the unit suite without Docker or JDT-LS, Mongo integration separately, then fixture/JDT-LS and real Indexer-to-Query contract checks. Build both images and run the image isolation scripts shown in the repository README. Query image checks reject JGit, JDT, LSP4J, JDT-LS, repository worktree paths, and AI model/chat/embedding artifacts.

## Legacy cutover record

The only supported reproduction point for the pre-split single application is pinned revision `9ff90b7e51eafbb6ef954e4ae0cc8a6a22173f9c`, immediately before split revision `2f1d0e8deadbd552b6ef2b5253a4252debe9631f`. Current CI and deployment never build or pull a legacy image and do not depend on a compatibility file.
