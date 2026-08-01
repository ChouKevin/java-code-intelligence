# Java Semantic Service Domain Model

This document defines the vocabulary used by source extraction, semantic resolution, call graphs, discovery APIs, and follow-up requests. Read it before changing identity or metadata types.

## Identity levels

- `JavaTypeIdentity` is a canonical Java nominal name such as `com.example.order.Order`. It may identify repository source or an external dependency and is not navigable by itself.
- `SourceTypeIdentity` combines a `JavaTypeIdentity` with a repository-relative source file. It proves that the declaration is navigable in the analyzed repository snapshot.
- `MethodTarget` combines a `SourceTypeIdentity`, method name, and canonical parameter signature. It identifies one source method declaration.
- `SourceMemberIdentity` identifies a type member or a method-scoped lexical declaration. Method-scoped identities include their declaration range because names may repeat in different scopes.

Do not promote a written or resolved name to `SourceTypeIdentity` unless a repository-relative declaration file is known.

## Type-reference evidence

`TypeReference` preserves the source use-site shape. Its projections have different meanings:

- `writtenType()` is source spelling, including generic arguments and array dimensions.
- `resolvedNamedType()` is the nominal declaration identity proved by binding, when one exists.
- `resolvedTypeName()` is the resolved use-site name consumed by discovery and concept projection.
- `sourceDefined()` means the resolved named declaration came from source input. It does not prove that the declaration is navigable in the current repository.

For an array such as `Order[][]`, `resolvedNamedType()` is `com.example.order.Order`, while `resolvedTypeName()` is `com.example.order.Order[][]`. Array dimensions belong to the use-site type and are intentionally retained in type-member responses, follow-up terms, and current string-based type-usage concept identities.

## Source metadata aggregate

`SourceTypeMetadata` is the authoritative aggregate for one source declaration:

- `SourceTypeDeclaration` owns source identity, kind, declaration range, source slice, and declaration flags.
- `SourceTypeRelationships` owns typed superclass and implemented-interface evidence.
- `SourceTypeMembers` owns fields and methods.
- `FrameworkTypeFacts` owns extracted framework classifications.
- `CompilationUnitContext` owns package, imports, extraction outcome, and compilation-unit evidence.

Components derive repeated display values from stronger identities. Do not add a second stored source file, package, class name, or fully qualified name.

## Location vocabulary

- `SyntaxRange` is an AST/source-extraction range in a repository file.
- `SemanticRange` is a location returned by semantic resolution tooling.
- `CallSiteRange` is the invocation location attached to a call-graph edge.

These ranges belong to different evidence producers and are not interchangeable even when their line and character coordinates happen to match.

## Resolution vocabulary

- **written**: source text as authored, without binding authority.
- **resolved**: a binding or resolver produced an authoritative canonical identity.
- **source-defined**: the resolved declaration was reported as source input; this alone does not supply a repository path.
- **source-bound**: a `SourceTypeIdentity` proves both nominal identity and repository-relative declaration file.
- **unresolved**: no authoritative target was established.
- **ambiguous**: more than one authoritative candidate remains and policy cannot select one.

Never use a written simple name as proof of a resolved or source-bound identity.

## HTTP conversion ownership

`MethodTargetHttpMapper` is the only production boundary that constructs or flattens the HTTP `MethodTarget` representation. API DTOs stay flat for protocol stability; domain and application code use composed identities.

## Agent follow-up example

1. A type-member query returns field `orders`, `writtenType: Order[][]`, and `resolvedType: com.example.order.Order[][]`.
2. The response follow-up searches the exact resolved use-site type. The agent must not remove `[][]` and pretend the field is scalar.
3. A source-symbol or concept result can identify the nominal element declaration `com.example.order.Order` and its repository-relative source file.
4. The agent then uses that source-bound identity to request type members, method source, implementation discovery, or a call graph.

## Deferred concept identity cleanup

`TypeUsageConceptIdentity` still stores a resolved use-site type string. R3 will inventory concept-family equality and ordering, replace string-composed type identity with typed identity where protocol-safe, and define any required migration explicitly. R2 does not introduce a second compatibility representation.
