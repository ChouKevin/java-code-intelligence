# Repository Guidelines

## Project Structure & Module Organization

`java-semantic-service` is a standalone Java 21/Spring Boot Maven project; it is not a module of the root application. Production code lives under `src/main/java/com/java/semantic`. Packages separate HTTP contracts (`api`), graph construction (`callgraph`), repository lifecycle (`repository`), JDT LS integration (`semantic`), JDT syntax extraction (`syntax`), route matching (`trie`), and shared identity/diagnostics/configuration. Runtime configuration is in `src/main/resources/application.yml`; the versioned contract is `src/main/resources/openapi/semantic-api-v1.yaml`. Tests mirror production packages under `src/test/java`, with fixture repositories in `src/test/resources/fixtures`.

Domain vocabulary is summarized in [`docs/domain-model.md`](docs/domain-model.md).

## Build, Test, and Development Commands

Run commands from the repository root:

```bash
mvn -f java-semantic-service/pom.xml clean test
mvn -f java-semantic-service/pom.xml spring-boot:run
JDTLS_HOME=/opt/jdtls mvn -f java-semantic-service/pom.xml -Pjdtls-it test
```

The first command runs the ordinary suite without launching JDT LS. The profile command enables real-server integration tests and requires a valid `JDTLS_HOME`.

## Coding Style & Naming Conventions

Use four-space indentation and explicit Java types; never use `var`. Prefer records for immutable value objects. Packages are lowercase, classes use PascalCase, and methods/fields use camelCase. Avoid raw `== null`/`!= null`; use `Objects`, Spring assertions, or collection/string utilities. Use meaningful domain exceptions and `@Slf4j` parameterized logs. Do not log credentials, source bodies, or sensitive filesystem paths.

## Testing Guidelines

Use JUnit 5, Spring Boot Test, Mockito, and ArchUnit. Name unit tests `*Test`; reserve `*IT` plus the `jdtls-it` tag for tests requiring a real language server. Add focused functional coverage for behavior changes and run `ArchitectureTest` when package dependencies change. Before a PR, run the full ordinary suite; run the JDT LS profile for lifecycle or semantic-resolution changes.

## Commit & Pull Request Guidelines

Follow the existing Conventional Commit style: `feat(semantic): ...`, `fix(semantic): ...`, `test(semantic): ...`, or `docs(semantic): ...`. Keep commits small and behavior-focused. PRs should explain the problem and observable behavior, link the issue when available, list verification commands, and call out API, OpenAPI, configuration, concurrency, or process-lifecycle effects.

## Security & Configuration

Copy values from `.env.example`; never commit tokens or credentials. Treat `SEMANTIC_API_TOKEN`, Git credentials, repository data, and JDT LS workspace data as sensitive. Preserve fail-closed authorization and read-policy behavior when changing endpoints.
