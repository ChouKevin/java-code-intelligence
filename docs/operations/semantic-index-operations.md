# Semantic Query Operations

## Runtime and transport boundary

Indexer owns Git, JDT LS, generation validation and publication. Query reads Mongo only: the current published generation or a separately prepared, authorization-checked READY review. It has no checkout, source fallback, model runtime or control over Indexer.

Send `X-Api-Token: $SEMANTIC_QUERY_API_TOKEN` to Query HTTP and its `/mcp`. Indexer has a separate private `/mcp` and admin credential. Both transports in each application use one facade, binder, result contract and error mapper. MCP success carries readable JSON TextContent equal to structuredContent; application errors use the same safe body as HTTP with `isError: true`. Unknown fields, explicit nulls and incompatible union fields are rejected rather than ignored.

## Thirteen shared operations

| MCP operation | HTTP route | Purpose |
| --- | --- | --- |
| `list_repositories` | `GET /api/v1/repositories` | Visible configured/published discovery, including unindexed repositories |
| `get_context` | `POST /api/v1/context` | CURRENT, REVIEW, COMMIT or RANGE discovery; never prepares work |
| `search_code` | `POST /api/v1/search-code` | Ranked symbol search with kind/package/path filters |
| `list_files` | `POST /api/v1/files` | Admitted files in the exact context |
| `search_text` | `POST /api/v1/search-text` | Case-sensitive literal CODE-source search, including Unicode |
| `read_source` | `POST /api/v1/source` | FACT or FILE source with exact ranges and continuation |
| `list_entry_points` | `POST /api/v1/entry-points` | Indexed HTTP/EVENT/MQ/SCHEDULE evidence and typed filters |
| `get_outline` | `POST /api/v1/outline` | TYPE or FILE declarations in source order |
| `find_relations` | `POST /api/v1/relations` | CALLERS, CALLEES, IMPLEMENTATIONS or REFERENCES |
| `list_git_branches` | `POST /api/v1/git/branches` | Prepared immutable catalog |
| `list_git_commits` | `POST /api/v1/git/commits` | Prepared history for the exact branch |
| `compare_revisions` | `POST /api/v1/git/comparisons` | Prepared direct before-to-after changes |
| `get_file_diff` | `POST /api/v1/git/file-diff` | Patch pages for a returned changeId |

GET discovery uses query parameters; the other routes use JSON bodies identical to MCP arguments. The authoritative field/schema definitions are [Query OpenAPI](../../semantic-query/src/main/resources/openapi/semantic-api-v1.yaml). Removed per-current/per-review tools and routes have no aliases.

## Discover once, retain exact contexts

Current discovery:

```json
{"repositoryId":"orders","selector":{"kind":"CURRENT"}}
```

A configured repository without a publication is `UNINDEXED`. An optional active job is separate from current: admission, metadata refresh and a running BUILD do not imply a published revision. With a publication, `get_context` returns its READY `context`, indexed timestamp, preparation branch, bounded structural overview and guide state. A later BUILD does not replace those fields until publication; failure preserves the old pointer. Inspect the original Indexer job for its terminal failure.

Copy the returned context unchanged into navigation requests:

```json
{
  "context":{"kind":"CURRENT","repositoryId":"orders","revision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
  "query":"OrderService",
  "kinds":["TYPE"]
}
```

The SHA above is a shape example, not an existing publication. All actual revisions must come from discovery. If CURRENT returns `REVISION_OUTDATED`, rediscover current and obtain new fact IDs; do not replay a fact-bound request with a substituted SHA.

Review discovery accepts one of:

```json
{"repositoryId":"orders","selector":{"kind":"REVIEW","reviewId":"returned-review-id"}}
```

```json
{"repositoryId":"orders","selector":{"kind":"COMMIT","revision":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}}
```

```json
{"repositoryId":"orders","selector":{"kind":"RANGE","beforeRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","afterRevision":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}}
```

Discovery distinguishes `NOT_PREPARED`, `PREPARING`, `FAILED` and `READY`. Only READY supplies readable side/comparison contexts. For each side copy `before.context` or `after.context` into the same tools used for current. REVIEW context contains `kind`, `repositoryId`, `reviewId`, `side` and exact `revision`. It is not replaced when current or a branch moves. A root commit has `before.kind: EMPTY_TREE` and no BEFORE semantic context. Equal-SHA sides may share fact IDs but still require their own side membership.

Copy `comparisonContext` into comparison and patch requests. It contains repositoryId, reviewId and exact `before`/`after` endpoints, not caller-chosen generation/snapshot IDs. COMMIT uses first parent, including merge commits; RANGE preserves the supplied order, including equal/reversed/divergent endpoints. Neither means merge-base/PR diff. Review preparation does not require or move current.

## Navigation and bounded source

- `search_code` requires 2–256 characters. Exact name/signature ranks precede prefix/token ranks. This is code search, not natural-language or vector retrieval. Optional `kinds`, `packagePrefix` and `path` constrain results. Exact phases can use equality indexes; prefix/token phases still scan ordered generation candidates. Page limits are not a documents-examined or latency guarantee.
- `get_outline.target` is `{"kind":"TYPE","factId":"…"}` or `{"kind":"FILE","path":"…"}`. It returns declaration summaries, including nested types and mapper statements where indexed, not full bodies.
- `find_relations` takes `factId` and `relation: CALLERS|CALLEES|IMPLEMENTATIONS|REFERENCES`. Resolution status and unresolved evidence remain explicit. An empty caller/reference set does not prove dead code or absence of dynamic use.
- `list_entry_points` optionally filters kind, handlerName/packagePrefix and corresponding HTTP/event/MQ/schedule properties. Use the input schema's compatible combinations. Unsupported or absent extraction is not a fabricated zero-coverage business conclusion.
- `read_source.target` is `{"kind":"FACT","factId":"…","contextLines":0}` or `{"kind":"FILE","path":"…","startLine":1}`. FACT surrounding context is 0–20 lines. `maxLines` defaults to 200, maximum 500; each response is also bounded to 64 KiB. FACT admission retains semantic authorization; whole FILE/Git reads require whole-source permission.
- Source positions use UTF-16 coordinates over exact UTF-8 stored bytes. Follow the returned cursor, not a guessed next line: a long line can span pages. Empty EOF ranges are valid, CRLF stays intact, and partial-line/completeness fields distinguish a boundary from truncation.
- Lists default to 20, maximum 100; commit history defaults to 10. Retain exact filters/context with cursors. `page.hasMore` is authoritative. Literal text search has a scan budget and may return an empty page with continuation; that is not a completed negative search.

## Source policy, guide and coverage

Readable payloads are selected Java, mapper XML within imported roots and at most one explicitly opted-in valid regular Markdown guide. Configuration/wiring files, unrelated documents, symlinks and submodules are not a source fallback. Both rename endpoints must pass policy before a patch is persisted.

The overview reports sealed facts and scoped coverage, not business completeness. Restricted readers receive authorization-scoped counts and explicit omitted metrics; they do not receive hidden package/module aggregates. `compare_revisions.policyCoverage` is required even for an empty result: `excludedChanges` and general reason counts (`UNSUPPORTED_PATH`, `SYMLINK`, `SUBMODULE`, `OUTSIDE_SOURCE_POLICY`) distinguish excluded entries from unchanged endpoints without disclosing paths/content. Whole-comparison Git tools are denied under granular source restrictions rather than publishing unscoped counts.

`projectGuide.state` is `DISABLED`, `ABSENT`, `INVALID` or `AVAILABLE`. Missing/invalid documents do not prevent valid code publication. AVAILABLE carries path/digest, importedRevision and author provenance; `freshness: NOT_VERIFIED` is not a correctness or staleness verdict. A document-only commit makes analyzed/imported SHA differ legitimately. Source marks it `PROJECT_GUIDE`; its body does not become facts, relations or default text-search evidence. Historical sides never borrow the current guide. Authoring/review rules are in the [external prompt](repository-context-prompt.md).

## Preparation, errors and recovery

Only the private Indexer MCP admits `prepare_codebase`, `refresh_repository_metadata` and `prepare_review`; each needs a saved canonical lowercase UUID requestId. `get_job` accepts repositoryId plus exactly one of jobId/requestId. After a lost response, recover that original identity even after completion/restart/later work. `REQUEST_NOT_FOUND` is not authorization to resend; `REQUEST_ID_REUSED` creates no new job. An inspected failure can lead to an explicitly authorized new intent with a new UUID.

Unknown/denied data stays fail-closed. Missing, non-READY, failed and mismatched review reads use `REVIEW_NOT_FOUND`, `REVIEW_NOT_READY`, `REVIEW_FAILED` and `REVIEW_CONTEXT_MISMATCH`. Unknown branch or unprepared history is an error, not a fake empty catalog. Storage unavailability is a safe error, not empty evidence or an online rebuild. See [deployment and operation](semantic-review.md) and [Git evidence](git-review-context.md).
