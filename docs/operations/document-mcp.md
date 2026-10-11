# Document MCP operations

The `document-query` read-only server serves pregenerated navigation from one operator-managed central Git repository. It is independent of Source Query and Indexer. It reads exact Git objects plus an external publication selection; it never fetches, checks out, builds target projects, runs an LLM, prepares source/indexes, publishes documents or deletes Git objects. The same executable offers a separate, explicitly invoked offline local-Git command described below. Elasticsearch, arbitrary historical document search and a publisher API are not provided.

Navigation is always `NOT_VERIFIED`. Document provenance, operator approval and source readiness are different facts. Business conclusions still require exact-revision Source Query evidence. Read the [query skill](../../skills/query-business-code/SKILL.md) and [central author handoff](repository-context-prompt.md#central-document-handoff).

## Deployment boundary

Build from the reactor root with Java21 and Maven3.9.x:

```bash
mvn --batch-mode --no-transfer-progress -pl document-query -am -DskipTests package
```

The executable is `document-query/target/document-query-0.0.1-SNAPSHOT-exec.jar`. Its component scan is confined to `com.java.semantic.document`; only JDK-only `semantic-model` contracts are shared. The module does not depend on Source Query/Indexer implementation, SQLite, JavaParser/JDT or the AI model stack. JGit reads the document repository, not business repositories. Existing source images copy the additional reactor POM for Maven resolution but gain no document Git mount or document runtime. No separate document image is included.

Configure these required values explicitly:

| Setting | Meaning |
| --- | --- |
| `document.repository-id` | Stable document repository ID, same validator as source IDs |
| `document.git-directory` | Absolute managed Git directory, for example an operator-owned bare repository |
| `document.publication-file` | Absolute external selection file; not a branch or Git HEAD |
| `document.collection-purpose` | `TEST_ONLY` or `APPROVED`; no implicit mode |
| `DOCUMENT_QUERY_API_TOKEN` | Separate nonblank document read credential |

Supply Git objects and the selection read-only to the service account. Their parent directories must not be writable by untrusted document authors; filesystem links cannot redirect the configured namespace. Keep source storage, Indexer admin data, business clones and Git credentials out of this process. Use controlled TLS/ingress for non-loopback access. Do not reuse the Source Query or Indexer token.

After the operator has populated valid objects/catalog and selection, one local launch form is:

```bash
: "${DOCUMENT_REPOSITORY_ID:?Set the approved document repository ID}"
: "${DOCUMENT_GIT_DIRECTORY:?Set the absolute managed Git directory}"
: "${DOCUMENT_PUBLICATION_FILE:?Set the absolute selection file}"
: "${DOCUMENT_COLLECTION_PURPOSE:?Set TEST_ONLY or APPROVED}"
: "${DOCUMENT_QUERY_API_TOKEN:?Inject the separate read secret}"
export DOCUMENT_QUERY_API_TOKEN
java -Xms64m -Xmx256m -XX:ActiveProcessorCount=2 \
  -jar document-query/target/document-query-0.0.1-SNAPSHOT-exec.jar \
  --server.address=127.0.0.1 --server.port=8082 \
  --document.repository-id="$DOCUMENT_REPOSITORY_ID" \
  --document.git-directory="$DOCUMENT_GIT_DIRECTORY" \
  --document.publication-file="$DOCUMENT_PUBLICATION_FILE" \
  --document.collection-purpose="$DOCUMENT_COLLECTION_PURPOSE"
```

These heaps are bounded verification examples, not a production capacity guarantee. Check host/backing-host headroom before local verification; see [testing](testing.md). Missing/invalid configuration or invalid initial publication fails startup. There is no empty-success fallback.

## Local Git document commands

Use [manage-repository](../../skills/manage-repository/SKILL.md) to route source,
index, drafting and document-update operations. Document updates need no business
repo preparation, remote, network access or Query token. The CLI exits before
Spring/web startup; the MCP server remains read-only.

For this command, initialize an ordinary local Git repository with `git init -b main`
and configure its **local** `user.name` and `user.email`. No remote is allowed.
Set `DOCUMENT_GIT_DIRECTORY` to its absolute `.git` directory, not its worktree.
The read-only server also supports bare repositories; the writer does not.
Export these four settings explicitly:

```bash
export DOCUMENT_REPOSITORY_ID=local-guides
export DOCUMENT_GIT_DIRECTORY=/absolute/owned/local-guides/.git
export DOCUMENT_PUBLICATION_FILE=/absolute/owned/publication.json
export DOCUMENT_COLLECTION_PURPOSE=TEST_ONLY
```

Initial generated files are owner-only (0600). Before a separate-UID reader uses
the namespace, the operator must provision its authorized read/traverse access
to Git objects, directories and selection. Replacement preserves existing owner,
group and POSIX mode, plus ACLs where the JDK exposes `AclFileAttributeView`; failure
to reproduce supported attributes refuses replacement. On Linux, use POSIX
owner/group/mode for this CLI's reader-access policy: standard JDK providers do
not expose extended POSIX ACLs, so **ACL-only reader namespaces are unsupported**.
The command neither grants public access nor verifies another service UID.

Use `APPROVED` only with the separately approved exact document content and source
identities. TEST_ONLY fixtures do not acquire production approval. Place Markdown
directly in `repositories/<sourceRepositoryId>/` in the document worktree.
`--file` is a filename in that directory, not an arbitrary path.

```bash
java -jar document-query/target/document-query-0.0.1-SNAPSHOT-exec.jar --document-local --help
java -jar document-query/target/document-query-0.0.1-SNAPSHOT-exec.jar --document-local \
  update --source "example@${FULL_SOURCE_SHA}" --file description.md --file guide.md
java -jar document-query/target/document-query-0.0.1-SNAPSHOT-exec.jar --document-local status
java -jar document-query/target/document-query-0.0.1-SNAPSHOT-exec.jar --document-local \
  activate --revision "${FULL_DOCUMENT_SHA}"
```

`update` accepts repeated `--related <repositoryId>@<fullSourceSha>` and
`--withdraw <fullDocumentSha>`, plus one `--review-reference <text>`; APPROVED
updates require that reference. `activate` also accepts repeated `--withdraw`.
Source and document SHAs are separate full lowercase 40-character identities.
`--help` needs no configuration; `status` does not create a publication.

The command generates `navigation-catalog.json`, validates the complete retained
selection with the reader's admission rules, commits raw named files and catalog,
then atomically replaces the external selection. It refuses any pre-existing staged
changes or manual catalog edits. Unrelated tracked/untracked working files and
unselected committed catalog entries remain untouched. Hooks, clean filters,
checkout/reset/clean and Git GC do not run. An unchanged update reuses its commit.

At most eight revisions may be retained. A ninth refuses unless explicit
`--withdraw` choices make room; there is no automatic eviction. Withdrawal changes
reader membership, not Git history. Activating an older retained commit does not
move the Git branch. Keep retained objects protected from unrelated external GC.

Success emits one JSON result with `serviceVerified:false`: it proves local state,
not what a running MCP instance has loaded. Verify through actual `search_documents`
and `read_document`, checking document SHA, source contexts and publication status.
Exit codes are 0 success, 2 invalid/admission refusal, 3 busy/concurrent change,
4 I/O failure. Errors include a phase and, when available, the candidate SHA.
If commit succeeds but activation fails, old selection remains; inspect `status`
and explicitly `activate --revision <candidate>` after resolving the reported cause.
Do not reset other edits or automatically repeat an uncertain update. A writer
lock left by a killed process requires proving that no writer is alive before
operator-authorized removal; do not delete another process's lock.


## Central Git catalog

Each selected actual commit contains `navigation-catalog.json` and its listed Markdown blobs. Files must be directly under `repositories/<primaryRepositoryId>/`; recommended filenames are `description.md` and `guide.md`, with other `.md` filenames in the same directory allowed. Membership is the catalog, not tree existence. Unlisted files are ignored. Nested directories, unsafe path segments, links/gitlinks, malformed UTF-8, empty Markdown and controls other than CR/LF/TAB are rejected. Git filters, hooks, submodule initialization and LFS hydration do not run.

Catalog object fields:

- `formatVersion`: integer1.
- `documentRepositoryId`: the configured document ID.
- `documents`: array of unique entries. Each has `path`, lowercase64-character SHA-256 `contentDigest` of the raw UTF-8 blob, `primaryContext`, `relatedContexts`, `reviewStatus`, and optional `reviewReference`.
- Each source context is exactly `{repositoryId, revision}`, using that source repository's full lowercase40-character SHA. Related repository IDs are unique and cannot repeat the primary ID.
- `reviewStatus` is `DRAFT` or `APPROVED`; `APPROVED` requires a nonblank review reference. Unknown fields, wrong types, duplicate JSON fields and excessive nesting are invalid.

The catalog does **not** contain its own commit hash: the service returns the actual selected Git commit. Metadata is supplied operator provenance, not server verification of human review, Markdown claims, deployment or source readiness. A changed body requires its changed digest/review metadata in a new document commit. Authoring keeps the existing Markdown templates; the handoff adds the catalog without asking authors to copy a self-hash into documents.

`TEST_ONLY` accepts either row status and reports it honestly; a fixture remains test-only even if its row says APPROVED. `APPROVED` collections reject an entire candidate containing DRAFT rows. Fixture-use permission does not approve real draft contents. Both modes return `trust: NOT_VERIFIED`.

Illustrative catalog shape only: the repeated hash values below are **not**
actual admitted objects/digests. Replace them with the approved source SHA and
the digest computed from the exact document bytes; never send these examples.

```json
{
  "formatVersion": 1,
  "documentRepositoryId": "navigation",
  "documents": [{
    "path": "repositories/member/guide.md",
    "contentDigest": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
    "primaryContext": {
      "repositoryId": "member",
      "revision": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    },
    "relatedContexts": [],
    "reviewStatus": "DRAFT"
  }]
}
```

## Selection, replacement and retention

The external selection is a strict JSON object with integer `formatVersion:1`, matching `documentRepositoryId`, a full `activeRevision`, and a nonempty unique `retainedRevisions` array containing active. All revisions are exact Git commits; branch names, abbreviated SHAs and inferred HEAD are not accepted.

Illustrative external selection, with unrelated example document hashes:

```json
{
  "formatVersion": 1,
  "documentRepositoryId": "navigation",
  "activeRevision": "dddddddddddddddddddddddddddddddddddddddd",
  "retainedRevisions": [
    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
    "dddddddddddddddddddddddddddddddddddddddd"
  ]
}
```

Both actual retained commits must exist and validate. The example source SHA
`aaaa…` is not either document SHA and does not become source current.

The local command performs the publication protocol; users need not hand-edit JSON:

1. Make validated Git objects and generated catalog bytes available.
2. Write the complete admitted selection to a sibling temporary file.
3. Atomically replace the configured selection on the same filesystem.
4. Verify actual MCP identity and `publicationStatus`; local replacement alone is not service acceptance.

The service observes the bounded selection on each operation. Unchanged accepted bytes reuse immutable snapshots. Changed selection acquires a fair exclusive catalog lock, waits for all old read leases to drain, re-observes the selection, builds/reuses every selected revision, and rechecks the exact selection bytes before activation. Readers hold leases through synchronous bounded result materialization. The writer downgrades its lock into a read lease without an activation gap. At most current plus one candidate full state are constructed; a third publication waits, rather than retaining arbitrarily many leased states. Closed leases drop their whole-state references.

If a running service cannot admit an update, it serves its actual last-good identities with `publicationStatus: UPDATE_REJECTED` and a sanitized `publicationErrorCode`. It does not pretend the requested new revision was published. A selection changing again during validation is rejected for that operation; the next operation observes the new bytes. Rejected selections are retried on later operations, so restoring a missing object can recover without changing the selection bytes. There is no permanent negative cache or mtime-only shortcut.

Startup/restart reconstructs only the configured selection. An invalid selection after restart fails closed; the previous process's in-memory last-good state is not durable recovery. Restore a valid selection before restarting.

Retained revisions remain eligible for exact reads and already-issued search continuation. Removing a revision withdraws new access after existing leases drain. The service neither expires revisions on a timer nor runs Git GC. Operators must keep every retained Git object reachable/protected from collection; selection alone is not a Git ref. There is no automatic fallback to active when an exact revision disappears.

## MCP client contract

Register a **second** MCP server at this service's `/mcp` URL, with `X-Api-Token` sourced from `DOCUMENT_QUERY_API_TOKEN`. Give query agents only this read surface and the separate Source Query MCP, never Indexer admin access. Project instructions/the query skill tell the client when to use document navigation; tool registration alone does not guarantee natural-language triggering.

The endpoint is stateless WebMVC MCP with exactly two read-only tools. Every `/mcp` request is authenticated; wrong/missing credentials receive HTTP401 before catalog/tool access. It does not share Source Query's initialized session or session ID. `/actuator/health` is minimal liveness without catalog/source details; other application routes are denied. There is no REST business API or MCP write tool. Executable schemas live in `document-query/src/main/resources/document-tools.json`, separately from Source Query OpenAPI.

### search_documents

Arguments: required `query`, optional `limit` (default10), optional `cursor`. Queries are nonblank literal, case-sensitive substrings: no regex, stemming, normalization, fuzzy matching or relevance ranking. A first call searches only the active document revision. Do not send a source SHA, document revision selector, branch or unknown field.

ATX headings outside backtick/tilde fenced blocks define sections; a headingless prefix starts at original line1 with an empty title. Each section contributes at most its first match; ancestors do not duplicate child matches. Results are ordered by Java `String.compareTo` path, then original section start line. Headings/snippets are bounded Unicode-code-point excerpts, with explicit truncation flags. A snippet is navigation, not a complete section or evidence of a business claim.

The envelope returns `documentContext`, `collectionPurpose`, `trust`, `publicationStatus`, optional `publicationErrorCode`, `items`, and `page {returned,hasMore,nextCursor?}`. Each hit carries exact context/path/digest, `section {startLine,title,titleTruncated}`, snippet/flag, independent primary/related source contexts and actual review metadata.

Continue with the **same query and requested limit** plus the returned cursor. Continuation pins the initial document revision even after active changes. The cursor is process-authenticated, not a durable bookmark; tampering, changed query/limit or restart returns `INVALID_ARGUMENT`. A withdrawn pinned revision returns `DOCUMENT_REVISION_NOT_AVAILABLE`. Do not rewrite or decode the token to choose another revision. Whole trailing hits may be removed for the response budget; the continuation points to the first omitted hit and retains the original requested limit.

### read_document

Arguments: exact `documentContext {documentRepositoryId,documentRevision}`, `path`, and `sectionStartLine` copied from a hit. Every locator is required; it must identify a catalog member and actual section boundary. No nearest-section guess or current fallback is made.

The result is the **entire bounded Markdown document**, not just the matching section: `scope: FULL_DOCUMENT`, `startLine:1`, original `endLine`, lossless `content`, `blobId`, `contentDigest`, `byteLength`, selected section, and the same version/source/review/publication metadata. CRLF and EOF are preserved. There is no read pagination, summary, elision or duplicate separate section body. Read the full body, including headers, contexts, evidence rows and limitations, before using a navigation claim.

Cite documents as `documentRepositoryId@fullDocumentCommit:path#L<sectionStartLine>`. Keep this distinct from source citations `repositoryId@fullSourceSHA:path:Lstart-Lend`. The document commit never goes into a Source Query request. Metadata's analyzed source revisions do not override user-selected exact source contexts. A title is a display excerpt, not an identity. For another section, use the returned original full body to identify its exact original heading line rather than inventing a locator.

### Errors and fixed bounds

Application errors return `isError:true` with `{code,message}` in matching text/structured forms. Messages are fixed and sanitized; inspect both protocol and tool error status. Codes: `INVALID_ARGUMENT`, `DOCUMENT_REPOSITORY_NOT_FOUND`, `DOCUMENT_REVISION_NOT_AVAILABLE`, `DOCUMENT_NOT_FOUND`, `DOCUMENT_SECTION_NOT_FOUND`, `DOCUMENT_CATALOG_INVALID`, `DOCUMENT_CAPACITY_EXCEEDED`, `DOCUMENT_RESULT_TOO_LARGE`, `DOCUMENT_BUSY`, `DOCUMENT_TIMEOUT`, `DOCUMENT_UNAVAILABLE`.

| Bound | Maximum |
| --- | --- |
| Publication selection | 16KiB |
| Retained revisions | 8 |
| Catalog JSON per revision | 256KiB |
| Documents per revision | 128 |
| Raw Markdown per document | 64KiB |
| Total retained Markdown | 16MiB |
| Sections per document / total retained | 256 / 8192 |
| UTF-8 path bytes | 512 |
| Related source contexts per document | 32 |
| Heading / snippet Unicode code points | 160 / 240 |
| Query Unicode code points | 1–128, nonblank |
| Search limit | 1–20, default10 |
| HTTP request body, including chunked | 16KiB |
| Cursor bytes | 4KiB |
| Complete serialized MCP result | 512KiB, text plus structured envelope |
| Active operations | 2; third immediately DOCUMENT_BUSY |
| Cooperative operation / subscribed tool-response timeout | 5s / 10s |

The single cooperative deadline covers selection observation, lock waiting, reload, search/read and synchronous complete-result size checking. Expired/cancelled work does not publish an expired candidate or serve an expired result. The installed SDK2.0.0 stateless `requestTimeout` setting is not enforced by that SDK; the service instead uses supported stateless async tool registration with an owned ten-second response timer. The underlying query/materializer remains synchronous under its original resources. Two owned workers have no waiting work queue; rejected work returns `DOCUMENT_BUSY`.

Timeout returns a sanitized `DOCUMENT_TIMEOUT` and requests interruption, but permits/leases remain held until actual work exits. Local filesystem/JGit calls cannot be forcibly interrupted; response timeout is not a hard wall-clock worker-kill guarantee. Shutdown drains actual workers before closing their Git loader, so a non-returning filesystem call can delay shutdown rather than closing Git underneath it. Stateless cancellation notifications are acknowledged by the SDK without an active-request cancellation handler; acknowledgement does not prove interruption of another request.

Admission proves each full read fits the same actual MCP writer under the largest encoded section locator and worst permitted publication metadata. Runtime repeats that complete-envelope check. Logical byte/section bounds do not equal JVM RSS: JSON/UTF-16/object metadata, two full states during reload, bounded result buffers and runtime overhead also consume memory.
An oversized candidate's full contextual read is an admission-capacity refusal: last-good results report `publicationErrorCode: DOCUMENT_CAPACITY_EXCEEDED`, within both advertised schemas. `DOCUMENT_RESULT_TOO_LARGE` remains an actual tool-result error, not publication metadata.

## Verification and evidence

Use `bash scripts/test-document-mcp.sh` for both packaged native journeys after resource admission. They snapshot the executable into an exclusively owned temporary root, exercise real CLI/HTTP/MCP and exact Git blobs, including update, old-pin continuation, rollback and explicit withdrawal, reap owned child processes, remove private test storage/logs and retain sanitized acceptance. They do not authorize images, existing-service restarts or project commits.
Interrupted teardown still completes graceful/forced termination and restores owner interruption only after reaping. A running-ownership marker is cleared only after actual child exit; if termination remains unconfirmed, the launcher retains the private work root instead of deleting underneath a possibly live process.

External acceptance explicitly selects `gpt-6-luna`, gives the agent only Document MCP and Source Query MCP, and records the client/version/invocation, complete tool trace and source-grounded answer. Scenarios cover current and historical source contexts, thin/no-hit document guidance and document replacement. Do not preload document bodies or author traces, rewrite the agent answer, or substitute local reads/preparation calls for MCP evidence. Native and agent results are separate evidence in [testing](testing.md) and the [agent checklist](mcp-agent-acceptance.md).
