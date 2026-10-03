# Java Code Intelligence Roadmap

## Current delivery boundary

- Indexer resolves exact Git commits, stores durable jobs, and runs one job at a time through one dispatcher thread.
- Indexer performs checkout, JDT LS analysis, immutable generation writes, validation, sealing, and expected-parent pointer publication.
- Query serves sealed MongoDB generations through HTTP and MCP without Git, JDT LS, or an online source fallback.
- Indexer exposes metadata refresh, fixed-branch codebase preparation, explicit COMMIT/RANGE review preparation, and original-request job recovery over aligned HTTP/MCP contracts.
- Query exposes 13 unified evidence tools over the same HTTP/MCP application contract. READY reviews retain exact immutable BEFORE/AFTER membership; root commits use EMPTY_TREE and RANGE compares the supplied endpoints directly, not merge base.
- Schema version 4 and analyzer policy 3 require the documented clean cutover/rebuild or analyzer rebuild/reprepare sequence respectively; no backward decoder or distributed worker ownership is provided.
- Code-only source membership and one optional reviewed project guide are prepared before sealing. Generation coverage includes copied unresolved facts; guide text remains author-provided navigation with NOT_VERIFIED freshness.
- Repository onboarding is supported through Git URL/default-branch configuration, explicit Query source authorization and prepare_codebase. Startup registers repositories but does not index or summarize them.
- Public/synthetic fixtures provide real Git/JDT, Mongo publication, independent-process cold Query and HTTP/MCP parity evidence. Actual OMP/Codex review journeys are recorded; Claude model execution, private-repository completeness, deployed TLS and capacity remain unverified.

Start with [startup and repository onboarding](operations/semantic-review.md#startup-and-repository-onboarding).
Optional guide authoring uses the [shared external-agent prompt](operations/repository-context-prompt.md);
it is not a service-side model or an indexing prerequisite.

## Next

These are future work candidates, not additional capabilities delivered by the current release:

- Automate operator-led repository onboarding checks: Git access, fixed-branch configuration, Query authorization and preparation status, while retaining explicit administrator intent and the single dispatcher.
- Streamline the external guide-generation/manual-review handoff. Keep generation outside Semantic, use an independent approved clone, and preserve exact provenance and source-scope approval.
- Complete deployment-specific acceptance with separate credentials/Mongo roles, real TLS, backup/restore and reviewed graph retention; measure representative private-repository coverage and capacity rather than extrapolating from local fixtures.
- Complete the Claude model journey when authorized client authentication is available; tool discovery alone is not model acceptance.
- Add new tool projections only through the documented schema bootstrap, repository rebuild and Query release order.

## Deferred

- More than one Indexer process or distributed job ownership. This requires a new design; do not add leases or locks to the current dispatcher.
- LLM, chat, prompt, embedding, or vector-search integration inside Semantic.
- Query-side source checkout, repository caches, JDT/JDT LS, and synchronous repository mutation.
- Compatibility adapters, multi-version decoders, or legacy deployment paths.
- Automatic generation garbage collection until a retention policy is approved.
