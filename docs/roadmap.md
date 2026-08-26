# Java Code Intelligence Roadmap

## Current delivery boundary

- Indexer resolves exact Git commits, stores durable jobs, and runs one job at a time through one dispatcher thread.
- Indexer performs checkout, JDT LS analysis, immutable generation writes, validation, sealing, and expected-parent pointer publication.
- Query serves sealed MongoDB generations through HTTP and MCP without Git, JDT LS, or an online source fallback.
- Schema version 2 has no distributed worker ownership or backward decoder.
- Payment, order, and video Git fixtures prove cold Query reads, exact revisions, two payment `v1` to `v2` transitions, and UAT-only reset isolation.

## Next

- Add new tool projections through the schema bootstrap, repository rebuild, and Query release order.
- Operate separate credentials, Mongo roles, TLS, backups, and reviewed generation retention.
- Add repositories by configuring a Git URL and default branch, then submit ordinary index jobs.

## Deferred

- More than one Indexer process or distributed job ownership. This requires a new design; do not add leases or locks to the current dispatcher.
- LLM, chat, prompt, embedding, or vector-search integration inside Semantic.
- Query-side source checkout, repository caches, JDT/JDT LS, and synchronous repository mutation.
- Compatibility adapters, multi-version decoders, or legacy deployment paths.
- Automatic generation garbage collection until a retention policy is approved.
