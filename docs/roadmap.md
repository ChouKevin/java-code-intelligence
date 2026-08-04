# Java Code Intelligence Roadmap

## Direction

This roadmap belongs to the independent service whose canonical repository is
`git@github.com:ChouKevin/java-code-intelligence.git`. It records direction, not delivery dates or
compatibility promises. The service remains the sole owner of repository lifecycle, Java semantic
analysis, structured discovery, source navigation, API-route indexing, HTTP contracts, and MCP
query projection.

The roadmap uses five states:

- `ACTIVE`: the delivery boundary currently being implemented
- `NEXT`: the next accepted delivery boundary
- `PLANNED`: an accepted direction whose detailed contract still requires a milestone spec
- `DEFERRED`: intentionally excluded until concrete evidence justifies the complexity
- `COMPLETE`: a delivery boundary whose completion gate has been met

## R0: History-preserving repository extraction

Status: `COMPLETE`

- Extracted committed `java-semantic-service/` history into the root of `java-code-intelligence`
- Established the extracted baseline on `uat` and `main`
- Preserved HTTP, OpenAPI, MCP, monitoring, security, revision, and typed-identity contracts
- Retained the Agent consumer handoff as the gate before deleting the embedded source
- Prohibited long-lived dual-source and compatibility implementations

## R1: Independent delivery baseline

Status: `ACTIVE`

- Produce one independently buildable Java 21 container with a pinned JDT LS distribution
- Keep ordinary tests independent of a live JDT LS process
- Run explicit JDT LS integration verification for lifecycle and semantic-resolution changes
- Use existing tests as the authority for the versioned OpenAPI document and exact 17-tool MCP catalog
- Keep image publication, registry authentication, and deployment ownership out of scope

## R2: Agent consumer cutover

Status: `PLANNED`

- Point the Agent's existing HTTP adapter at the independently deployed service
- Preserve opaque `repoId` and revision-pinned queries across the repository boundary
- Keep the Agent runtime independent of service implementation classes and build artifacts
- Verify the five existing code-intelligence planning capabilities against the external service
- Retain MCP as a parallel transport; decide Agent MCP adoption in a separate Agent-side milestone
- Delete the embedded service only after external contract and recovery checks pass

## R3: Dependency-aware and multi-module semantic workspaces

Status: `PLANNED`

- Support Maven multi-module repositories without inventing a second semantic model
- Accept CI-produced compile dependency JARs through an explicit workspace contract
- Bind external types, framework annotations, implementations, and inherited members when evidence is available
- Scope dependency artifacts by repository and revision
- Invalidate JDT LS workspaces and semantic caches when dependency identity changes
- Expose unresolved external evidence without guessing or silently degrading identity

## R4: Navigation and identity consolidation

Status: `PLANNED`

- Consolidate typed identity vocabulary across definition, reference, implementation, hierarchy, graph, and source APIs
- Use one source-location and range convention across HTTP and MCP projections
- Ensure every bounded result carries enough identity for a deterministic follow-up query
- Add definition, references, implementations, type hierarchy, call hierarchy, and diagnostics only through shared application services
- Remove replaced DTOs and code paths rather than retaining compatibility aliases
- Address cross-module identities as part of the typed model instead of encoding module knowledge into strings

## R5: Business-oriented Java discovery

Status: `PLANNED`

- Discover Spring injection relationships with explicit resolution evidence
- Discover HTTP endpoints through the existing entry-point and source-identity contracts
- Discover JPA entities, repositories, and repository-method relationships
- Discover Java-owned MQ producers and consumers
- Preserve source ranges and typed follow-ups so an Agent can inspect the implementing method or declaration
- Prefer focused discovery endpoints over unrestricted literal or shell search

## R6: Multi-repository capacity and observability

Status: `PLANNED`

- Define JDT LS workspace capacity and eviction for multiple active repositories
- Keep caches repository- and revision-scoped with observable invalidation reasons
- Benchmark representative large repositories for startup, query latency, memory, and response bounds
- Retain sanitized HTTP and MCP monitoring without logging source bodies, credentials, or raw JSON-RPC payloads
- Return bounded evidence with explicit completeness and executable follow-up information

## Deferred directions

Status: `DEFERRED`

- General-purpose interprocedural data-flow analysis
- Arbitrary repository-wide literal search
- Shell-script and YAML business interpretation
- External-state mutation through MCP query tools
- A shared Java library between the Agent and semantic service
- Compatibility DTOs for contracts that have no running consumer

Each deferred item requires a separate design decision with a concrete Agent use case, authority
boundary, bounded cost, recovery behavior, and security analysis before implementation.
