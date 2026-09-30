# Semantic Query Operations

## Runtime boundary

Run Indexer and Query separately. Indexer performs Git checkout and offline JDT LS analysis before it seals and publishes a MongoDB generation. Query starts without JDT LS, a checkout, or an Indexer process; it serves only persisted data from the current sealed generation. Query also reads separately prepared READY Git evidence from Mongo; it never opens a repository to do so.

Send `X-Api-Token: $SEMANTIC_QUERY_API_TOKEN` on every Query HTTP request and on every MCP request to `/mcp`.

## Query surface

MCP exposes exactly thirty tool names:

- `list_repositories`, `get_repository`, `search_code`, `get_fact_source`
- `list_entry_points`, `find_api_routes`, `find_event_listeners`, `list_type_members`
- `find_method_implementations`, `find_references`, `find_callers`, `find_callees`
- `list_git_branches`, `list_git_commits`, `compare_revisions`, `get_file_diff`
- `list_files`, `read_file`, `search_text`
- `get_review`, `review_search_code`, `review_get_fact_source`, `review_list_entry_points`
- `review_find_api_routes`, `review_find_event_listeners`, `review_list_type_members`
- `review_find_method_implementations`, `review_find_references`, `review_find_callers`, `review_find_callees`

The HTTP surface has the equivalent thirty operations:

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
| `get_review` | `GET /api/v1/repositories/{repositoryId}/reviews/{reviewId}` |
| `review_search_code` | `POST /api/v1/reviews/search-code` |
| `review_get_fact_source` | `POST /api/v1/reviews/fact-source` |
| `review_list_entry_points` | `POST /api/v1/reviews/entry-points` |
| `review_find_api_routes` | `POST /api/v1/reviews/api-routes` |
| `review_find_event_listeners` | `POST /api/v1/reviews/event-listeners` |
| `review_list_type_members` | `POST /api/v1/reviews/type-members` |
| `review_find_method_implementations` | `POST /api/v1/reviews/method-implementations` |
| `review_find_references` | `POST /api/v1/reviews/references` |
| `review_find_callers` | `POST /api/v1/reviews/callers` |
| `review_find_callees` | `POST /api/v1/reviews/callees` |

## Revision recovery

`list_repositories` accepts pagination only and returns each visible repository's current identity and revision. `get_repository` accepts only `repositoryId` and returns the current identity and revision for that repository. Copy those returned values into the other ten semantic repository-scoped requests.

Query does not silently read an older or newer semantic revision. One of the other ten semantic requests with a stale revision returns `REVISION_OUTDATED` and includes `currentRevision`. Read that value and retry the same semantic operation with the replacement revision. A stale semantic request is not retryable without changing its revision. Git review operations are historical: retain their returned immutable catalog, history, comparison, and snapshot IDs with the exact requested historical SHA rather than replacing it with the current semantic revision.

## READY review operation

`POST /index/repositories/{repositoryId}/reviews` and the private Indexer's
`prepare_review` MCP tool admit an explicit `COMMIT` or `RANGE` selection with a
client-generated canonical UUID `requestId`. They are not Query operations and
never capture or move current. Ordinary BUILD preparation is not a review.
Save requestId before submission, require the typed `202` job/review identity,
and check `operation: REVIEW` plus the original selection before polling.
After a lost response, look up that same requestId with
`GET /index/repositories/{repositoryId}/jobs?requestId=…`; do not select a later
or merely active job. A client outage does not authorize resubmission.
Follow the [review admission and stop procedure](semantic-review.md#current-generations-and-review-preparation)
for exact selector, terminal recovery, and explicit retry rules.

`get_review` is the only public review discovery operation. It returns immutable
BEFORE/AFTER revisions, side generation identities, comparison ID, and snapshot IDs after
the review is READY. Use those values unchanged. The ten side operations are
`review_search_code`, `review_get_fact_source`, `review_list_entry_points`,
`review_find_api_routes`, `review_find_event_listeners`,
`review_list_type_members`, `review_find_method_implementations`,
`review_find_references`, `review_find_callers`, and `review_find_callees`.
Every side request requires `repositoryId`, `reviewId`, `side`, and its exact
side `revision`; Query rejects a mismatch rather than selecting current.

`COMMIT` uses first-parent → commit (or empty tree for a root); `RANGE` uses the
exact before → after direction, including equal, reverse, and divergent commits.
Neither is a merge-base or PR-diff claim. Query exposes neither PREPARING/FAILED review semantic data
nor review-owned Git snapshots/comparisons until the owner/READY membership gate
passes. A known Git ID is not a bypass. Unknown/denied, preparing, failed, and
wrong-context requests map respectively to `REVIEW_NOT_FOUND`,
`REVIEW_NOT_READY`, `REVIEW_FAILED`, and `REVIEW_CONTEXT_MISMATCH`.

See [Semantic review deployment and operation](semantic-review.md) for private
networking, release order, OMP evidence workflow, and retention.
