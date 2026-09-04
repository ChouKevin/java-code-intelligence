# Semantic Query Operations

## Runtime boundary

Run Indexer and Query separately. Indexer performs Git checkout and offline JDT LS analysis before it seals and publishes a MongoDB generation. Query starts without JDT LS, a checkout, or an Indexer process; it serves only persisted data from the current sealed generation.

Send `X-Api-Token: $SEMANTIC_QUERY_API_TOKEN` on every Query HTTP request and on every MCP request to `/mcp`.

## Query surface

MCP exposes exactly these twelve raw tool names:

- `list_repositories`, `get_repository`, `search_code`, `get_fact_source`
- `list_entry_points`, `find_api_routes`, `find_event_listeners`, `list_type_members`
- `find_method_implementations`, `find_references`, `find_callers`, `find_callees`

The HTTP surface has the equivalent twelve routes:

| Operation | HTTP route |
| --- | --- |
| `list_repositories` | `GET /api/v1/repositories` |
| `get_repository` | `GET /api/v1/repositories/{repositoryId}` |
| `search_code` | `POST /api/v1/search-code` |
| `get_fact_source` | `POST /api/v1/fact-source` |
| `list_entry_points` | `POST /api/v1/entry-points` |
| `find_api_routes` | `POST /api/v1/api-routes` |
| `find_event_listeners` | `POST /api/v1/event-listeners` |
| `list_type_members` | `POST /api/v1/type-members` |
| `find_method_implementations` | `POST /api/v1/method-implementations` |
| `find_references` | `POST /api/v1/references` |
| `find_callers` | `POST /api/v1/callers` |
| `find_callees` | `POST /api/v1/callees` |

## Revision recovery

`list_repositories` returns each visible repository with its current revision. `get_repository` returns the current revision for one known repository. Copy those exact values into every repository-scoped request.

Query does not silently read an older or newer revision. A request with a stale revision returns `REVISION_OUTDATED` and includes `currentRevision`. Read that value and retry the same operation with the replacement revision. A stale request is not retryable without changing its revision.
