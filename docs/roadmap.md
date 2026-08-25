# Java Code Intelligence Roadmap

## Current delivery boundary

The service operates as two images: an asynchronous offline Indexer and a read-only Query service. The Indexer owns Git and JDT-LS execution; Query serves sealed MongoDB generations through HTTP and MCP. CI verifies the dependency and image isolation boundary.

## Next

- Operate index jobs with bounded worker capacity, claim renewal, fencing, validation, and sealing.
- Keep Query read-only and projection-version aware.
- Rebuild payment, order, and video fixtures through the deterministic production pipeline.
- Maintain separate credentials, Mongo roles, TLS, backups, and non-destructive schema procedures.

## Deferred

- LLM, chat, prompt, embedding, or vector-search integrations.
- Query-side source checkout, repository caches, JDT/JDT-LS, and synchronous repository mutation.
- Legacy single-image compatibility adapters, migration decoders, or unpinned cutover images.
- Automatic generation garbage collection until a retention policy is explicitly designed.
