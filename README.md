# Java Semantic Service

Java Semantic Service is a standalone Java 21 service for repository lifecycle,
JDT LS-backed semantic analysis, structured discovery, API-route indexing, and
bounded call-graph queries. The service is intentionally suitable for future
extraction into its own repository: its HTTP and MCP adapters are inbound
transport boundaries over the same framework-neutral semantic capabilities.

The root Agent continues to call the HTTP adapter. MCP is a parallel transport
for MCP-aware clients; this milestone does not connect the root Agent runtime
to an MCP client.

## Runtime prerequisites

- Java 21
- Maven 3.9 or newer
- Spring Boot 4.1.0
- Spring AI 2.0.0
- A JDT Language Server installation for real semantic analysis
- Git access for repositories configured by the deployment

The ordinary Maven test suite does not launch JDT LS. A running service needs
JDT LS unless it is deliberately configured with `JDTLS_ENABLED=false` for an
environment that does not perform semantic analysis.

From the repository root:

```bash
mvn -f java-semantic-service/pom.xml clean test
mvn -f java-semantic-service/pom.xml spring-boot:run
```

For a real JDT LS installation, set `JDTLS_HOME` and use the service's
`jdtls-it` profile for integration verification:

```bash
JDTLS_HOME=/opt/jdtls mvn -f java-semantic-service/pom.xml -Pjdtls-it test
```

## Configuration and authentication

Set a non-empty `SEMANTIC_API_TOKEN` before exposing the service. Every `/v1`
HTTP request and every `/mcp` request must send the token in the
`X-Api-Token` header. Do not commit the token or put it in a checked-in HTTP
client environment file.

The repository and JDT LS defaults can be overridden with the environment
variables in `src/main/resources/application.yml`, including:

```bash
export SEMANTIC_API_TOKEN=<token>
export JDTLS_HOME=/opt/jdtls
export JDTLS_WORKSPACE_DATA_ROOT=/data/jdtls
export GIT_USERNAME=<read-only-git-user>
export GIT_TOKEN=<read-only-git-token>
```

Git credentials are used only by repository lifecycle operations. They are not
returned by the API and are not part of MCP tool inputs or outputs.

## Repository lifecycle and revisions

Repositories are identified by the opaque, validated `repoId`; clients do not
send local paths, remote URLs, credentials, or a repository object. The normal
HTTP lifecycle is:

```http
GET /v1/repositories
GET /v1/repositories/{repoId}
POST /v1/repositories/{repoId}/ensure
POST /v1/repositories/{repoId}/sync
POST /v1/repositories/{repoId}/checkout
```

Use `ensure` to create or prepare a configured repository, `sync` to update it,
and `checkout` to select a known revision. Then call `GET
/v1/repositories/{repoId}` and copy the returned `currentRevision` into the
next query's `expectedRevision`. A semantic query must carry both `repoId` and
`expectedRevision` (the catalog list/get operations are the exception). The
service rejects a query when the revision is no longer current; after a sync or
checkout, obtain a fresh revision instead of retrying the old request.

The revision is a snapshot guard, not a server-side client session. HTTP
requests and MCP tool calls are stateless and must repeat the identity and
revision on every query. Repository mutation endpoints remain HTTP-only; they
are not published as MCP tools.

## HTTP usage

All examples require `X-Api-Token`. The following route query is pinned to one
repository snapshot:

```http
POST https://semantic.example.invalid/v1/api-routes/lookup
X-Api-Token: <token>
Content-Type: application/json

{
  "repoId": "<repo-id>",
  "expectedRevision": "<40-hex-revision>",
  "apiPath": "/orders",
  "httpMethod": "GET"
}
```

The entry-point query carries the same revision as a required query parameter:

```http
GET https://semantic.example.invalid/v1/repositories/<repo-id>/entry-points?expectedRevision=<40-hex-revision>&types=API
X-Api-Token: <token>
```

The committed [`uat/structured-concept-discovery.http`](uat/structured-concept-discovery.http)
file contains revision-pinned HTTP discovery, source, and graph handoffs. It
uses placeholders only; populate the returned repository revision and typed
identities before running it.

## MCP usage

MCP is a stateless streamable HTTP transport at `/mcp`. A client must:

1. send an authenticated JSON-RPC `initialize` request with
   `MCP-Protocol-Version: 2025-06-18`
2. send `tools/list` to obtain the published catalog
3. send `tools/call` requests with the exact tool name and typed arguments
4. include `repoId` and `expectedRevision` in every repository-scoped query

No MCP session identifier is required for the stateless protocol. A minimal
client exchange is:

```http
POST https://semantic.example.invalid/mcp
X-Api-Token: <token>
MCP-Protocol-Version: 2025-06-18
Content-Type: application/json
Accept: application/json, text/event-stream

{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"example-client","version":"1"}}}
```

Then request `tools/list`, followed by a typed call such as:

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"semantic_list_entry_points","arguments":{"repoId":"<repo-id>","expectedRevision":"<40-hex-revision>","types":["API"]}}}
```

The service returns structured tool content. Discovery results may expose a
typed `availableFollowUps` entry; pass its `toolName` and `arguments` to the
next `tools/call` without inventing or translating an endpoint. The committed
[`uat/semantic-mcp-query.http`](uat/semantic-mcp-query.http) demonstrates this
flow, including a dynamically captured source-segment follow-up.

### Published MCP catalog

The catalog is exactly these 17 read-only, non-destructive, idempotent tools:

| Tool | HTTP operation |
| --- | --- |
| `semantic_analyze_incoming_call_graph` | `POST /v1/analyses/call-graphs/incoming` |
| `semantic_analyze_outgoing_call_graph` | `POST /v1/analyses/call-graphs/outgoing` |
| `semantic_discover_concepts` | `POST /v1/discovery/concepts` |
| `semantic_discover_event_listeners` | `POST /v1/discovery/event-listeners` |
| `semantic_discover_method_implementations` | `POST /v1/discovery/method-implementations` |
| `semantic_discover_type_members` | `POST /v1/discovery/type-members` |
| `semantic_find_internal_references` | `POST /v1/discovery/internal-references` |
| `semantic_get_evidence_source` | `POST /v1/discovery/evidence-source` |
| `semantic_get_method_source` | `POST /v1/discovery/method-source` |
| `semantic_get_repository` | `GET /v1/repositories/{repoId}` |
| `semantic_get_source_segment` | `POST /v1/discovery/source-segment` |
| `semantic_list_entry_points` | `GET /v1/repositories/{repoId}/entry-points` |
| `semantic_list_repositories` | `GET /v1/repositories` |
| `semantic_lookup_api_routes` | `POST /v1/api-routes/lookup` |
| `semantic_resolve_concept` | `POST /v1/discovery/concepts/resolve` |
| `semantic_resolve_source_symbol` | `POST /v1/discovery/source-symbols/resolve` |
| `semantic_suggest_api_routes` | `POST /v1/api-routes/suggest` |

The three repository mutation operations (`ensure`, `sync`, and `checkout`)
are deliberately absent from this catalog.

## Failures and recovery

| Situation | HTTP behavior | MCP behavior |
| --- | --- | --- |
| Missing or invalid token | `401` with `SEMANTIC_UNAUTHORIZED` | transport rejection, normally `401` |
| Malformed request or missing required input | `400` with `REQUEST_INVALID` | JSON-RPC transport/input error; invalid typed arguments are `INVALID_TOOL_INPUT` |
| Unknown MCP tool | not applicable | JSON-RPC `-32602` |
| Revision changed since the query was planned | `409` with `REPOSITORY_REVISION_MISMATCH` | typed tool failure with the same semantic error category |
| Route index absent or published for another revision | `409` with `API_ROUTE_INDEX_NOT_READY` | typed tool failure with the same semantic error category |
| Repository or semantic engine not ready | typed `409` or `503` service error | typed tool failure; retry only after readiness is restored |

`REPOSITORY_REVISION_MISMATCH` is resolved by reading the repository status and
reissuing the query with its new `currentRevision`. Do not silently substitute
the current revision for the caller's expected revision. For
`API_ROUTE_INDEX_NOT_READY`, wait for the repository analysis/index publication
to complete and retry with the same revision only when that revision remains
current.

## Monitoring and redaction

Both adapters emit request-correlation metadata, outcome categories, and
duration. HTTP monitoring records the method, path, status, error category, and
safe typed projections. MCP monitoring records the tool name and safe typed
request/response projections; transport monitoring records method, `/mcp`,
status, authentication error code, and duration.

The monitoring projection is allowlisted by field annotations. Source bodies
and source segments are marked `OMIT`, so source content is not logged.
Credentials, raw JSON-RPC payloads, repository paths, remote URLs, and other
unannotated sensitive values are not logged. In particular, a malformed or
authenticated MCP request must never cause its raw JSON-RPC body to appear in
service logs.

## Troubleshooting

- `401` or `SEMANTIC_UNAUTHORIZED`: confirm the service has a non-empty
  `SEMANTIC_API_TOKEN` and that the client sends the same value in
  `X-Api-Token`.
- `REPOSITORY_NOT_READY`: run the repository lifecycle operation, verify the
  repository status, and wait for JDT LS import/readiness.
- `REPOSITORY_REVISION_MISMATCH`: fetch the current status and replace every
  query's `expectedRevision`; do not mix revisions in one discovery sequence.
- `API_ROUTE_INDEX_NOT_READY`: the route index has not been published for the
  requested revision. Wait for analysis/indexing, then retry against the same
  revision if it is still current.
- `INVALID_TOOL_INPUT` or `REQUEST_INVALID`: obtain the input schema from
  `tools/list`, preserve required fields and enum values, and pass typed
  repository-relative identities rather than local paths or source text.
- `SEMANTIC_ENGINE_START_FAILED`, `SEMANTIC_REQUEST_TIMEOUT`, or
  `SEMANTIC_PROTOCOL_ERROR`: inspect JDT LS installation, workspace data
  permissions, configured timeouts, and service logs without exposing tokens or
  source content.

For a manual smoke test, configure the environment variables at the top of
`uat/semantic-mcp-query.http` and run each request in order. The file includes
the HTTP client response handler needed to invoke one server-provided
`availableFollowUps` request.
