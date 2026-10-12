# Document MCP operations

The `document-query` executable provides offline local-Git `update`, named `remove`, and `status` operations plus an independent read-only MCP server for pregenerated navigation. The writer generates the format-1 catalog, commits explicitly named Markdown, and atomically publishes a format-2 per-owner selection map; operators do not hand-author either JSON file. The server reads only the exact Git commit selected for each business-repository owner. It never fetches, checks out, builds target projects, runs an LLM, prepares source/indexes, authors documents, or deletes Git objects. Elasticsearch, MCP historical-document lookup and a publisher API are not provided.

Navigation is always `NOT_VERIFIED`. Document provenance, operator approval and source readiness are different facts. Business conclusions still require exact-revision Source Query evidence. Read the [query skill](../../skills/query-business-code/SKILL.md) and [central author handoff](repository-context-prompt.md#central-document-handoff).

## Local Git document commands

Use the offline workflow below; artifact packaging and read-only server launch
are in [Deployment boundary](#deployment-boundary). Operators update or remove
only authorized named files, inspect local `status`, then separately verify
actual Document MCP adoption.

Use [manage-repository](../../skills/manage-repository/SKILL.md) to route source,
index, authoring and document operations. Obtain approval for the local document
namespace, named Markdown, exact source contexts and intended update/removal.
Formal `APPROVED` updates require exact-content/source-identity human review;
named removals require explicit removal approval. `TEST_ONLY` drafting/use
requires current explicit user approval, not an old fixture's standing grant.
Document commands need no business-repo preparation, remote, network access or
Query token. The CLI exits before Spring/web startup; the MCP server is read-only.

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

Use `APPROVED` only after the exact content and source identities have been
approved; an `APPROVED` removal also needs its explicit removal reference.
`TEST_ONLY` does not confer production approval. Place Markdown directly in
`repositories/<primaryRepositoryId>/` in the document worktree. `--file` is a
filename in that owner directory, not an arbitrary path.

```bash
java -jar document-query/target/document-query-0.0.1-SNAPSHOT-exec.jar --document-local --help
java -jar document-query/target/document-query-0.0.1-SNAPSHOT-exec.jar --document-local \
  update --source "example@${FULL_SOURCE_SHA}" --file description.md --file guide.md
java -jar document-query/target/document-query-0.0.1-SNAPSHOT-exec.jar --document-local \
  remove --repository-id example --file obsolete-guide.md
java -jar document-query/target/document-query-0.0.1-SNAPSHOT-exec.jar --document-local status
```

`update` accepts repeated `--related <repositoryId>@<fullSourceSha>` and one
`--review-reference <text>`; formal `APPROVED` updates require that reference.
`remove` accepts `--repository-id <repo>`, repeated `--file <basename.md>`, and
optional `--review-reference <text>`; it does not require a new source SHA.
Formal removals require explicit approval. Source and document SHAs are separate
full lowercase 40-character identities. `--help` needs no configuration;
`status` reports the Git HEAD candidate and effective owner map.

The command generates `navigation-catalog.json` (format 1) and validates the
complete format-2 selection map before publishing. Each business repository has
one effective document commit for its group of named description/guide files.
The map is independent of Git HEAD: advancing A does not advance B's pointer.
Unselected rows/files and unrelated working files remain untouched. No catalog
or selection JSON is manually maintained.

The writer preserves Git parent history, index/ref compare-and-swap, same-
filesystem selection replacement, locks and supported permissions. It refuses
pre-existing staged changes, manual catalog changes, dirty content that would
be overwritten/discarded, unsafe paths, foreign ownership and unsupported
mixed Git state. A named removal may include an already-missing working file
only when its tracked/catalogued removal is explicitly named. Removing the final
document removes that owner from the map; removing the last owner publishes a
valid empty map. Neither operation deletes Git history or changes Source state.

Removal requires secure directory streams, stable file identities and POSIX
private-directory access; unsupported providers fail closed. It holds a no-follow
parent handle and captures the target in a mode-0700
`repositories/<owner>/.document-removal-<unique>/captured.md` before validating
identity/content and unlinking. Unexpected captures are retained, not overwritten
or discarded. If an ancestor was renamed, the capture can remain inside that
original parent. Inspect both the original and retained paths and preserve both
versions before an authorized, non-clobber recovery; never blanket-clean these
directories as temporary scaffolds. Successful validated removal cleans its
capture directory.

This relies on a protected namespace and trusted operators: same-UID actors must
not maliciously alter private captures or still-open captured files. Rename does
not revoke existing writable descriptors. Java has no directory-handle mkdir or
atomic conditional unlink/restore; setup races can leave an empty temporary
directory, and the command does not promise an all-actor filesystem transaction
or an atomic transaction spanning Git, index, working files and selection.

Mutation results identify the operation and affected owner, the Git
`candidateRevision`, the resulting owner-to-commit map, `commitCreated`,
`selectionChanged`, and `serviceVerified:false`. `status` omits an affected owner
and reports the Git HEAD candidate separately from the effective map. No single
document commit identifies the whole collection.

If commit, file synchronization and catalog generation succeed but selection
replacement fails, the old map remains effective. Inspect `status`, Git and
actual MCP, resolve the cause, then explicitly repeat the same authorized
`update` or `remove` with unchanged inputs. A same-HEAD update reuses its
committed candidate instead of making an empty duplicate. For a pending removal,
the named file/row can be absent from the candidate while still present in the
old effective owner publication; replay that same removal to publish the map.
Never pass an arbitrary prior commit, blindly retry uncertain work, or describe
a later changed-input candidate as recovery. If the operation completed and its
target is absent from both the effective map and Git, use `status` to resolve
the outcome; an unknown name is not a removal request.

Every effective owner change must be covered by the explicitly named files,
including a fresh operation after an earlier failed publication. Reusing a
pending candidate also requires the complete original operation/input identity,
including unchanged named files and the exact approval reference; the writer
records its digest in generated Git commit metadata. A subset or unrelated
operation cannot adopt pending changes. HEAD/branch, catalog and selection
changes during recovery refuse; inspect rather than bypassing the checks.

Failures preserve unrelated edits and report phase/candidate when available.
Do not reset or clean the worktree. A lock left by a killed writer may be
removed only after proving no writer is alive and obtaining operator authority.

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

## Central Git catalog

This schema is a technical reference for generated/admitted artifacts, not a
manual authoring workflow. Use the local command above for document updates.

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

## Selection, per-owner publication and cutover

The external selection is strict JSON format 2:

```json
{
  "formatVersion": 2,
  "documentRepositoryId": "navigation",
  "repositories": [
    {
      "repositoryId": "member-a",
      "documentRevision": "dddddddddddddddddddddddddddddddddddddddd"
    },
    {
      "repositoryId": "member-b",
      "documentRevision": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
  ]
}
```

The example commits are illustrative, not admitted Git objects. The exact
selection fields are `formatVersion`, `documentRepositoryId`, and `repositories`;
each unique owner entry contains exactly `repositoryId` and `documentRevision`.
Every listed owner must have at least one admitted document at that exact full
Git commit. `repositories: []` is valid and represents an empty document service.
Unknown fields, duplicates, legacy shapes, abbreviated IDs and branch names are
rejected. The generated `navigation-catalog.json` remains format 1; it indexes
named files in a Git tree and is not an additional business overview document.

There is one central local Git repository, but each business repository owns an
independent latest document group. A commit that updates A changes only A's map
entry; B may remain selected at an older commit, even when Git HEAD has moved.
For each selected commit the reader admits only that owner's rows and blobs.
`path` ownership must match `primaryContext.repositoryId`; `relatedContexts`
are provenance clues and never grant read ownership or trigger another owner's
publication. Git history keeps old commits, but an old commit not selected for
that owner is not historical Document MCP membership.

The reader atomically validates/replaces the complete map and reuses unchanged
owner/commit snapshots. Its limits apply once across the selected owner union.
A malformed candidate keeps the actual last-good map with
`publicationStatus: UPDATE_REJECTED`; a cold start with an invalid selection
fails closed. A valid empty map starts and serves successful empty searches.
Existing bounded read leases cover result materialization; after replacement,
a stale owner read fails explicitly instead of silently switching versions.

An A update, addition, or removal changes the global cursor fingerprint, so an
old global continuation returns `DOCUMENT_REVISION_NOT_AVAILABLE`. A scoped B
cursor remains usable unless B's effective commit changes. A B hit already
returned from a global page remains readable after A changes, because its full
context still names B's unchanged effective commit. An old A context fails even
if its Git commit still exists or is selected for another owner. A true owner
no-op changes no effective pointer and invalidates neither scope. Scope, query,
limit and cursor authentication errors remain `INVALID_ARGUMENT`; process
restart retains existing process-secret cursor invalidation.

Legacy format-1 whole-collection selections and ownerless document contexts are
not compatible. For any separately authorized deployment cutover, preserve the
central Git history and existing selection, create a fresh selection path,
explicitly publish each intended owner, verify the new reader, and only then
switch the named service under separate authority. Existing service settings and
data are not migrated or reset automatically. The former activate/withdraw
commands and old active/retained selection shape are cutover/history only, not
current operator workflows. Invalid selection after restart fails closed.

Publishing an owner absent from the new map is not an existing-owner no-op.
Explicitly name its complete intended group and supply the exact approved
content/provenance. If an unchanged HEAD does not record this operation, the
writer creates a forward publication-intent commit with the same tree rather
than adopting a foreign candidate as recovery. This can advance document Git
without changing Markdown or fabricating source identities. An exact matching
pending first-publication operation reuses its candidate; existing-owner recovery
still requires its original complete input identity. Other owners' pointers and
the separately preserved old selection do not change.

## MCP client contract

Register a **second** MCP server at this service's `/mcp` URL, with `X-Api-Token` sourced from `DOCUMENT_QUERY_API_TOKEN`. Give query agents only this read surface and the separate Source Query MCP, never Indexer admin access. Project instructions/the query skill tell the client when to use document navigation; tool registration alone does not guarantee natural-language triggering.

The endpoint is stateless WebMVC MCP exposing read-only `list_documents`, `search_documents` and `read_document`. Every `/mcp` request is authenticated; wrong/missing credentials receive HTTP401 before catalog/tool access. It does not share Source Query's initialized session or session ID. `/actuator/health` is minimal liveness without catalog/source details; other application routes are denied. There is no REST business API or MCP write tool. Executable schemas live in `document-query/src/main/resources/document-tools.json`, separately from Source Query OpenAPI.

### list_documents

Use this when no keyword is known. Arguments are optional `repositoryId`,
`limit` (default10, maximum20) and `cursor`; no query/revision/path selector.
Omitted owner browses all effective owners; a valid unknown owner succeeds empty.
Each document appears once, ordered by existing Java String full-path order.
There is no body, snippet, historical membership or new stored catalog.

The envelope uses the same owner/publication/trust/page metadata as search.
Each row includes its full `documentContext`, `path`, `contentDigest`,
`byteLength`, first actual `section {startLine,title,titleTruncated}`,
`primaryContext`, `relatedContexts` and review metadata. A headingless prefix
uses its real line1 entry, not an invented title. Pass that exact context/path
and section.startLine to `read_document`; it returns the complete document.
Global rows may contain different owner commits; there is no global document SHA.

Keep scope and requested limit unchanged with the next cursor. Listing/search
tokens are operation-bound and cannot be interchanged. Effective owner-map
changes expire global lists; only the changed owner's scoped lists expire.
An unchanged B context/page remains valid after A updates, and no-op publication
does not expire either scope. Stale versions return
`DOCUMENT_REVISION_NOT_AVAILABLE`; tamper, changed parameters, cross-operation
tokens or process restart return `INVALID_ARGUMENT`. A fresh query observes
the actual latest effective map, including last-good `UPDATE_REJECTED`.

Complete rows may be trimmed for the512KiB full result budget; continue with
the original limit to obtain the first omitted document. A single row/envelope
that cannot fit fails explicitly. Listing shares existing operation permits,
deadlines and read-lease ownership through complete result materialization.

### search_documents

Arguments: required `query`; optional `limit` (default 10), `cursor`, and
`repositoryId`. Omitted `repositoryId` means global search; a valid owner with
no selected documents returns a successful empty search. Blank/invalid owners
are `INVALID_ARGUMENT`. Queries are nonblank literal, case-sensitive
substrings: no regex, stemming, normalization, fuzzy matching or relevance
ranking. Do not send a source SHA, document revision selector, branch or unknown
field.

ATX headings outside backtick/tilde fenced blocks define sections; a headingless
prefix starts at original line 1 with an empty title. Each section contributes
at most its first match; ancestors do not duplicate child matches. Global
results are ordered by Java `String.compareTo` full path, then original section
start line. Headings/snippets are bounded Unicode-code-point excerpts with
explicit truncation flags. A snippet is navigation, not a complete section or
business evidence.

The envelope contains `documentRepositoryId`, optional selected `repositoryId`,
`collectionPurpose`, `trust`, `publicationStatus`, optional
`publicationErrorCode`, `items`, and `page {returned,hasMore,nextCursor?}`.
There is no top-level `documentContext`: each hit has its own
`documentContext {documentRepositoryId,repositoryId,documentRevision}`, exact
path/digest, section locator, snippet, independent primary/related source
contexts and review metadata. Optional fields follow the NON_ABSENT convention.

Continue with the same query, requested limit and owner scope plus the returned
cursor. A scoped cursor binds only that owner's effective full document SHA; a
global cursor binds the canonical active owner map. Any effective owner addition,
update or removal expires a global cursor; a scoped cursor expires only when its
owner changes. An identical owner no-op does not expire either scope. Cursor
operation/scope/query/limit are authenticated; tampering, changed bindings or process
restart return `INVALID_ARGUMENT`, while a changed effective scope returns
`DOCUMENT_REVISION_NOT_AVAILABLE`. Do not rewrite the token or context. Whole
trailing hits may be trimmed for response budget; continuation points to the
first omitted hit and retains the original requested limit.

### read_document

Arguments: complete `documentContext {documentRepositoryId,repositoryId,documentRevision}`,
`path`, and `sectionStartLine` copied from a listing row or search hit. The context must identify the
central document repository and exact effective commit for that path's owner.
Every locator is required; the path must be owned by the context and the line
must be an actual section boundary. No nearest-section guess or current fallback.

The result is the **entire bounded Markdown document**, not just the matching section: `scope: FULL_DOCUMENT`, `startLine:1`, original `endLine`, lossless `content`, `blobId`, `contentDigest`, `byteLength`, selected section, and the same version/source/review/publication metadata. CRLF and EOF are preserved. There is no read pagination, summary, elision or duplicate separate section body. Read the full body, including headers, contexts, evidence rows and limitations, before using a navigation claim.

Cite documents as `documentRepositoryId@fullDocumentCommit:path#L<sectionStartLine>`. Keep this distinct from source citations `repositoryId@fullSourceSHA:path:Lstart-Lend`. The document commit never goes into a Source Query request. Metadata's analyzed source revisions do not override user-selected exact source contexts. A title is a display excerpt, not an identity. For another section, use the returned original full body to identify its exact original heading line rather than inventing a locator.

### Errors and fixed bounds

Application errors return `isError:true` with `{code,message}` in matching text/structured forms. Messages are fixed and sanitized; inspect both protocol and tool error status. Codes: `INVALID_ARGUMENT`, `DOCUMENT_REPOSITORY_NOT_FOUND`, `DOCUMENT_REVISION_NOT_AVAILABLE`, `DOCUMENT_NOT_FOUND`, `DOCUMENT_SECTION_NOT_FOUND`, `DOCUMENT_CATALOG_INVALID`, `DOCUMENT_CAPACITY_EXCEEDED`, `DOCUMENT_RESULT_TOO_LARGE`, `DOCUMENT_BUSY`, `DOCUMENT_TIMEOUT`, `DOCUMENT_UNAVAILABLE`.

| Bound | Maximum |
| --- | --- |
| Publication selection | 16 KiB |
| Selected business-repository owners | 128 |
| Catalog JSON per selected Git commit | 256 KiB |
| Selected documents across all owners | 128 |
| Raw Markdown per document | 64 KiB |
| Selected Markdown across all owners | 16 MiB |
| Sections per document / across all selected owners | 256 / 8192 |
| UTF-8 path bytes | 512 |
| Related source contexts per document | 32 |
| Heading / snippet Unicode code points | 160 / 240 |
| Query Unicode code points | 1–128, nonblank |
| Listing / search limit | 1–20, default10 |
| HTTP request body, including chunked | 16KiB |
| Cursor bytes | 4KiB |
| Complete serialized MCP result | 512KiB, text plus structured envelope |
| Active operations | 2; third immediately DOCUMENT_BUSY |
| Cooperative operation / subscribed tool-response timeout | 5s / 10s |

The single cooperative deadline covers selection observation, lock waiting, reload, listing/search/read and synchronous complete-result size checking. Expired/cancelled work does not publish an expired candidate or serve an expired result. The installed SDK2.0.0 stateless `requestTimeout` setting is not enforced by that SDK; the service instead uses supported stateless async tool registration with an owned ten-second response timer. The underlying query/materializer remains synchronous under its original resources. Two owned workers have no waiting work queue; rejected work returns `DOCUMENT_BUSY`.

Timeout returns a sanitized `DOCUMENT_TIMEOUT` and requests interruption, but permits/leases remain held until actual work exits. Local filesystem/JGit calls cannot be forcibly interrupted; response timeout is not a hard wall-clock worker-kill guarantee. Shutdown drains actual workers before closing their Git loader, so a non-returning filesystem call can delay shutdown rather than closing Git underneath it. Stateless cancellation notifications are acknowledged by the SDK without an active-request cancellation handler; acknowledgement does not prove interruption of another request.

Admission proves each full read fits the same actual MCP writer under the largest encoded section locator and worst permitted publication metadata. Runtime repeats that complete-envelope check. Logical byte/section bounds do not equal JVM RSS: JSON/UTF-16/object metadata, two full states during reload, bounded result buffers and runtime overhead also consume memory.
An oversized candidate's full contextual read is an admission-capacity refusal: last-good results report `publicationErrorCode: DOCUMENT_CAPACITY_EXCEEDED`, within both advertised schemas. `DOCUMENT_RESULT_TOO_LARGE` remains an actual tool-result error, not publication metadata.

## Verification and evidence

Latest-document-navigation ordinary checks and packaged native journeys passed; see the testing guide for counts and platform limits.
Root-owned verification must cover owner-isolated reads, mixed-owner global
pages/cursors, scoped versus global expiry, no-op and rejected candidates,
named/last-file removal, empty selection, inspected same-operation recovery,
restart behavior and unchanged Source pins. Current execution results and
pending gates are recorded separately in the testing guide.

Fresh external-agent scenarios have also executed, with recovery cases recorded
separately from clean passes. Future runs must still select `gpt-6-luna` explicitly,
use only Document MCP and Source Query MCP read access, and preserve the complete
tool trace and source-grounded answer.
Native and agent evidence remain separate; historical baseline evidence and
prior failed answers are documented in [testing](testing.md#current-verification-status).
