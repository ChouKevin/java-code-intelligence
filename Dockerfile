FROM maven:3.9.11-eclipse-temurin-21 AS builder

WORKDIR /workspace

COPY pom.xml ./
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline

COPY src ./src
RUN mvn --batch-mode --no-transfer-progress -DskipTests clean package

FROM eclipse-temurin:21-jre-jammy AS jdtls

RUN apt-get update \
    && apt-get install --no-install-recommends --yes ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

COPY scripts/install-jdtls.sh scripts/jdtls-release.env /usr/local/lib/jdtls/
RUN chmod 755 /usr/local/lib/jdtls/install-jdtls.sh \
    && /usr/local/lib/jdtls/install-jdtls.sh /opt/jdtls

FROM eclipse-temurin:21-jre-jammy

RUN groupadd --system --gid 10001 semantic \
    && useradd --system --uid 10001 --gid semantic --home-dir /app --create-home semantic \
    && install --directory --owner=semantic --group=semantic /data/repos /data/jdtls

WORKDIR /app

COPY --from=jdtls /opt/jdtls /opt/jdtls
RUN chown --recursive semantic:semantic /opt/jdtls/config_linux
COPY --from=builder /workspace/target/java-semantic-service-*.jar /app/java-semantic-service.jar

ENV JDTLS_HOME=/opt/jdtls
ENV JDTLS_WORKSPACE_DATA_ROOT=/data/jdtls

USER semantic

ENTRYPOINT ["java", "-jar", "/app/java-semantic-service.jar"]
