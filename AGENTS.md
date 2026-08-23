# Repository Guidelines

## Project Structure & Module Organization

`java-semantic-service` is a Java 21/Spring Boot Maven reactor. Its canonical repository is `git@github.com:ChouKevin/java-code-intelligence.git`; the current directory is the temporary source for a history-preserving extraction. Never maintain parallel service implementations in both repositories. The root POM owns dependency and plugin management and orders the modules as `semantic-model`, `semantic-indexer`, and `semantic-query`. `semantic-model` is the shared model boundary; `semantic-indexer` contains the current Spring Boot application and all production code; `semantic-query` is an empty query boundary that currently depends only on `semantic-model`. In `semantic-indexer`, production code lives under `src/main/java/com/java/semantic`. Packages separate HTTP contracts (`api`), graph construction (`callgraph`), repository lifecycle (`repository`), JDT LS integration (`semantic`), JDT syntax extraction (`syntax`), route matching (`trie`), and shared identity/diagnostics/configuration. Runtime configuration is in `semantic-indexer/src/main/resources/application.yml`; the versioned contract is `semantic-indexer/src/main/resources/openapi/semantic-api-v1.yaml`. Tests mirror production packages under `semantic-indexer/src/test/java`, with fixture repositories in `semantic-indexer/src/test/resources/fixtures`.

Domain vocabulary is summarized in [`docs/domain-model.md`](docs/domain-model.md).

## Build, Test, and Development Commands

Run commands from the reactor root:

```bash
mvn clean test
mvn -pl semantic-indexer -am package && java -jar semantic-indexer/target/semantic-indexer-0.0.1-SNAPSHOT.jar
JDTLS_HOME=/opt/jdtls mvn -pl semantic-indexer -am -Pjdtls-it test
```

The first command runs the ordinary suite without Docker, Mongo, or a real JDT LS. The profile command includes only real-server JDT LS integration tests and requires a valid `JDTLS_HOME`. The `mongo-it` profile includes only Mongo/Testcontainers integration tests when they are added.

## Coding Style & Naming Conventions

Use four-space indentation and explicit Java types; never use `var`. Prefer records for immutable value objects. Packages are lowercase, classes use PascalCase, and methods/fields use camelCase. Avoid raw `== null`/`!= null`; use `Objects`, Spring assertions, or collection/string utilities. Use meaningful domain exceptions and `@Slf4j` parameterized logs. Do not log credentials, source bodies, or sensitive filesystem paths.

## Testing Guidelines

Use JUnit 5, Spring Boot Test, Mockito, and ArchUnit. Name unit tests `*Test`; reserve `*IT` plus the `jdtls-it` tag for tests requiring a real language server. Add focused functional coverage for behavior changes and run `ArchitectureTest` when package dependencies change. Before a PR, run the full ordinary suite; run the JDT LS profile for lifecycle or semantic-resolution changes.

## Commit & Pull Request Guidelines

Follow the existing Conventional Commit style: `feat(semantic): ...`, `fix(semantic): ...`, `test(semantic): ...`, or `docs(semantic): ...`. Keep commits small and behavior-focused. PRs should explain the problem and observable behavior, link the issue when available, list verification commands, and call out API, OpenAPI, configuration, concurrency, or process-lifecycle effects.

## Security & Configuration

Copy values from `.env.example`; never commit tokens or credentials. Treat `SEMANTIC_API_TOKEN`, Git credentials, repository data, and JDT LS workspace data as sensitive. Preserve fail-closed authorization and read-policy behavior when changing endpoints.
