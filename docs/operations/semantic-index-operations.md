# Semantic Query Operations

## Runtime boundary

Run Indexer and Query separately. Indexer performs Git checkout and offline JDT LS analysis before it seals and publishes a MongoDB generation. Query starts without JDT LS, a checkout, or an Indexer process; it serves only persisted data from the current sealed generation. Query also reads separately prepared READY Git evidence from Mongo; it never opens a repository to do so.

Send `X-Api-Token: $SEMANTIC_QUERY_API_TOKEN` on every Query HTTP request and on every MCP request to `/mcp`.

## Query surface

MCP exposes exactly nineteen raw tool names:

- `list_repositories`, `get_repository`, `search_code`, `get_fact_source`
- `list_entry_points`, `find_api_routes`, `find_event_listeners`, `list_type_members`
- `find_method_implementations`, `find_references`, `find_callers`, `find_callees`
- `list_git_branches`, `list_git_commits`, `compare_revisions`, `get_file_diff`
- `list_files`, `read_file`, `search_text`

The HTTP surface has the equivalent nineteen routes:

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
| `list_git_branches` | `POST /api/v1/git/branches` |
| `list_git_commits` | `POST /api/v1/git/commits` |
| `compare_revisions` | `POST /api/v1/git/comparisons` |
| `get_file_diff` | `POST /api/v1/git/file-diff` |
| `list_files` | `POST /api/v1/git/files` |
| `read_file` | `POST /api/v1/git/file` |
| `search_text` | `POST /api/v1/git/search` |

## Revision recovery

`list_repositories` accepts pagination only and returns each visible repository's current identity and revision. `get_repository` accepts only `repositoryId` and returns the current identity and revision for that repository. Copy those returned values into the other ten semantic repository-scoped requests.

Query does not silently read an older or newer semantic revision. One of the other ten semantic requests with a stale revision returns `REVISION_OUTDATED` and includes `currentRevision`. Read that value and retry the same semantic operation with the replacement revision. A stale semantic request is not retryable without changing its revision. Git review operations are historical: retain their returned immutable catalog, history, comparison, and snapshot IDs with the exact requested historical SHA rather than replacing it with the current semantic revision.
