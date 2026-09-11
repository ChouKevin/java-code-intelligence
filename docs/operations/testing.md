# Testing and verification

Run the commands below from the reactor root with Java 21 and Maven 3.9 or
later. Each profile sets the same Surefire include and exclude properties. Run
profiles as separate commands; `-Pmongo-it,jdtls-it` does not create a union of
the two test sets because later profile values overwrite the same properties.

| Entry point | Command | Required environment | Scope |
| --- | --- | --- | --- |
| Ordinary suite | `mvn --batch-mode --no-transfer-progress test` | Java 21 and Maven; no Docker, JDT LS, or deployment | Domain, architecture, application facade, and HTTP/MCP transport and wiring contracts |
| Mongo integration | `mvn --batch-mode --no-transfer-progress -Pmongo-it verify` | Java 21, Maven, and Docker | Mongo storage and generation contracts, including `SourceSliceContractIT`; this profile is separate from the ordinary suite |
| Full JDT LS profile | `JDTLS_HOME=/opt/jdtls mvn --batch-mode --no-transfer-progress -Pjdtls-it test` | Java 21, Maven, and a real JDT LS installation; some scenarios also need Docker | All scenarios tagged `jdtls-it`; a valid directory satisfies the basic prerequisite, while the existing startup checks report an incomplete installation |
| Local fixture contract | `JDTLS_HOME=/opt/jdtls scripts/test-indexer-query-contract.sh` | Java 21, Maven, Docker, and a real JDT LS installation | Payment, order, and video fixture indexing through the exporter and temporary Mongo to a temporary Query HTTP/MCP server, followed by projection evolution; it does not use an existing deployment |
| Deployed Query profile | `mvn --batch-mode --no-transfer-progress -Pdeployed-it test` | Java 21, Maven, an available Query deployment, and the three deployment variables below | The actual deployed Query HTTP/MCP contract |
| Fixture Maven tests | See commands below | Java 21 and Maven | Focused deterministic payment, order, and video fixture project tests |
| Image checks | See commands below | Docker | Indexer JDT LS image smoke and Query image isolation |

When a real JDT LS test is selected, an unset, empty, nonexistent, or
non-directory `JDTLS_HOME` is a test failure before expensive setup; it is not a
skip. Fixture-only checks remain runnable without `JDTLS_HOME`, and unrelated
platform assumptions remain independent checks.

For the deployed profile, use test-only placeholders and keep its token
separate from the production Query variable `SEMANTIC_QUERY_API_TOKEN`:

```bash
SEMANTIC_BASE_URL='<deployed-query-base-url>' \
SEMANTIC_API_TOKEN='<deployed-query-test-token>' \
SEMANTIC_UAT_REPOSITORY='<uat-repository-id>' \
mvn --batch-mode --no-transfer-progress -Pdeployed-it test
```

`SEMANTIC_API_TOKEN` is read by the deployed acceptance test and must
authenticate to the chosen Query. That Query's server configuration uses
`SEMANTIC_QUERY_API_TOKEN` for its read credential; the values may be the same
when the deployment is configured that way, but the variable names have
separate roles.

Run each deterministic fixture test as its own command:

```bash
mvn --batch-mode --no-transfer-progress -f semantic-indexer/fixtures/uat/payment-service/pom.xml test
mvn --batch-mode --no-transfer-progress -f semantic-indexer/fixtures/uat/order-service/pom.xml test
mvn --batch-mode --no-transfer-progress -f semantic-indexer/fixtures/uat/video-service/pom.xml test
```

Build and run the image checks independently:

```bash
docker build -f Dockerfile.indexer -t java-semantic-indexer:uat .
scripts/smoke-jdtls-image.sh java-semantic-indexer:uat
docker build -f Dockerfile.query -t java-semantic-query:uat .
scripts/test-query-image.sh java-semantic-query:uat
```

The local prerequisite regression is a cheap shell-only check and does not
start Maven, Docker, or JDT LS:

```bash
bash scripts/test-indexer-query-contract-prerequisites.sh
```

The contract script rejects an unset, empty, or non-directory `JDTLS_HOME`
before it starts Maven. It reports the variable and directory requirement
without echoing the configured path. It does not repair Docker, authentication,
or an incomplete JDT LS installation.

CI keeps all existing job IDs and display names. The
`indexer-jdtls-smoke` job selects the focused `InternalReferencesJdtLsIT`
scenario and retains `-Dsurefire.failIfNoSpecifiedTests=false` for upstream
reactor modules. The `indexer-query-contract` job keeps the local contract
script, whose real fixture phase is `FixtureFullIndexJdtLsIT` followed by
`ToolProjectionEvolutionIT`. The ordinary, Mongo, fixture, and image jobs keep
their existing commands. This smoke selection does not claim that CI runs every
`jdtls-it` scenario; use the full profile for that targeted local acceptance.

The local fixture SDK journey is a disposable-service check and does not
evaluate an LLM Agent. The deployed profile is the entry point for an existing
Query deployment; neither profile turns an empty indexed result into a business
conclusion.
