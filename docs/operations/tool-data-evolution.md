# Tool projection data evolution

Deploy projection changes in this order:

1. Implement the projector, validator/count coverage, and Mongo collection/index contract.
2. Run the versioned schema bootstrap with the schema-maintenance identity and verify the named indexes.
3. Deploy the Indexer.
4. Rebuild each repository and verify the sealed generation manifests.
5. Deploy Query only after the rebuilt projections are current.

The Indexer worker never creates or repairs schema. If a required index is missing or a same-named index has a conflicting definition, it stops before generation work and directs operators to schema maintenance. This works on standalone MongoDB; publication remains a fenced, single-document repository-pointer update and does not require transactions or a replica set.

Additive projection releases may add facts or fields and compatible named indexes. They must not change the meaning of a field or index consumed by the running Query release. For a changed meaning, add a new field or index name, or use a breaking schema version with an explicit maintenance cutover. Do not ship handwritten migration registries, multi-version decoders, or backward-compatibility readers.

A rebuild of the same source revision writes a new immutable generation and moves the repository pointer only after validation and sealing. It never updates the previous generation. Rollback means repointing to an already sealed generation; schema rollback remains a separate, non-destructive maintenance operation.
