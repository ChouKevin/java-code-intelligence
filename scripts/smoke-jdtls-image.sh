#!/usr/bin/env bash
set -euo pipefail

image_name="${1:?usage: smoke-jdtls-image.sh IMAGE}"

docker run --rm --entrypoint sh "${image_name}" -ceu '
  test -d /opt/jdtls/plugins
  test -d /opt/jdtls/config_linux
  test "$(find /opt/jdtls/plugins -name "org.eclipse.equinox.launcher_*.jar" | wc -l)" -eq 1
  test -d /data/repos
  test -f /opt/jdtls/lombok.jar
  echo "01f7b1a015e33e2b62d5f5f37053306357ab1415fd181fcba7794f5d198c1126  /opt/jdtls/lombok.jar" | sha256sum --check
  test -d /data/jdtls
  test "$(getent passwd 10001 | cut -d: -f1)" = analysis
  test -x /usr/bin/setpriv
'

docker run --rm --entrypoint sh "${image_name}" -ceu '
  test "$(stat -c "%u:%a" /data)" = "0:755"
  test "$(stat -c "%u:%a" /data/repos)" = "0:755"
  test "$(stat -c "%u:%a" /data/jdtls)" = "10001:755"
  probe=$(mktemp -d /data/repos/uid-boundary.XXXXXX)
  checkout="$probe/checkout"
  mkdir -p "$checkout/.git/objects"
  printf "ref: refs/heads/main\n" > "$checkout/.git/HEAD"
  chown -R 0:0 "$checkout/.git"
  chown 0:10001 "$checkout"
  chmod 3775 "$checkout"
  chmod 755 "$probe"
  if setpriv --reuid=10001 --regid=10001 --clear-groups --no-new-privs --bounding-set=-all \
      mv "$checkout/.git" "$checkout/.git.attacker"; then
    echo "analysis UID renamed checkout Git metadata" >&2
    exit 1
  fi
  if printf "ref: refs/heads/attacker\n" | setpriv --reuid=10001 --regid=10001 --clear-groups --no-new-privs --bounding-set=-all \
      tee "$checkout/.git/HEAD" >/dev/null; then
    echo "analysis UID wrote Git HEAD in place" >&2
    exit 1
  fi
  test "$(cat "$checkout/.git/HEAD")" = "ref: refs/heads/main"
  if setpriv --reuid=10001 --regid=10001 --clear-groups --no-new-privs --bounding-set=-all \
      mv "$checkout/.git/HEAD" "$checkout/.git/HEAD.attacker"; then
    echo "analysis UID renamed a Git control file" >&2
    exit 1
  fi
  if setpriv --reuid=10001 --regid=10001 --clear-groups --no-new-privs --bounding-set=-all \
      mv "$checkout" "$checkout.attacker"; then
    echo "analysis UID renamed the managed checkout root" >&2
    exit 1
  fi
  if setpriv --reuid=10001 --regid=10001 --clear-groups --no-new-privs --bounding-set=-all \
      mv /data/repos /data/repos.attacker; then
    echo "analysis UID renamed the managed repository parent" >&2
    exit 1
  fi
  if setpriv --reuid=10001 --regid=10001 --clear-groups --no-new-privs --bounding-set=-all \
      mv /data /data.attacker; then
    echo "analysis UID renamed the data root" >&2
    exit 1
  fi
  setpriv --reuid=10001 --regid=10001 --clear-groups --no-new-privs --bounding-set=-all \
    touch "$checkout/analysis-write-probe"
  rm -rf "$probe"
'


docker run --rm --entrypoint sh "${image_name}" -ceu '
  test "$(id -u)" = 0
  probe=$(mktemp -d /tmp/jdtls-checkout-boundary.XXXXXX)
  chmod 755 "$probe"
  mkdir -p "$probe/classes" "$probe/app"
  (
    cd "$probe/app"
    /opt/java/openjdk/bin/jar -xf /app/semantic-indexer.jar
  )
  cat > "$probe/CheckoutSecurityProbe.java" <<'"'"'JAVA'"'"'
package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.config.JdtLsProperties;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRuntime;
import org.eclipse.jgit.api.Git;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public final class CheckoutSecurityProbe {
    private static final long ANALYSIS_UID = 10001;
    private static final long ANALYSIS_GID = 10001;

    private CheckoutSecurityProbe() {
    }

    public static void main(String[] arguments) throws Exception {
        Path scenarios = Files.createDirectories(Path.of(arguments[0]));
        UserPrincipal analysis = Path.of("/").getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(Long.toString(ANALYSIS_UID));

        rejectAnalysisOwnedGitControl(scenarios, analysis);
        rejectHardLinkedControlWithoutSentinelMutation(scenarios);
        rejectAnalysisOwnedImmediateParent(scenarios, analysis);
        rejectReplaceableHigherAncestor(scenarios, analysis);
        allowSafeChain(scenarios);
        System.out.println("shipped JdtLsProcessFactory checkout-boundary probe completed");
    }

    private static void rejectAnalysisOwnedGitControl(Path scenarios, UserPrincipal analysis) throws Exception {
        Path caseRoot = Files.createDirectories(scenarios.resolve("analysis-owned-git"));
        Path managedParent = Files.createDirectories(caseRoot.resolve("managed"));
        Path checkout = createCheckout(managedParent);
        makeAnalysisWritable(checkout.resolve(".git"), analysis);
        Path data = Files.createDirectories(scenarios.resolve("analysis-owned-git-data"));
        assertRejectedWithoutRepair(
                "analysis-owned Git control",
                checkout,
                managedParent,
                data,
                snapshot(caseRoot, data));
    }

    private static void rejectHardLinkedControlWithoutSentinelMutation(Path scenarios) throws Exception {
        Path caseRoot = Files.createDirectories(scenarios.resolve("hard-linked-control"));
        Path managedParent = Files.createDirectories(caseRoot.resolve("managed"));
        Path checkout = createCheckout(managedParent);
        Path config = checkout.resolve(".git/config");
        Path sentinel = caseRoot.resolve("outside-control-sentinel");
        byte[] configBytes = Files.readAllBytes(config);
        Files.delete(config);
        Files.write(sentinel, configBytes);
        setMode(sentinel, 0644);
        Files.createLink(config, sentinel);
        if (!Files.isSameFile(config, sentinel)) {
            throw new IllegalStateException("the Git config fixture is not hard linked to its outside sentinel");
        }

        Path data = Files.createDirectories(scenarios.resolve("hard-linked-control-data"));
        assertRejectedWithoutRepair(
                "hard-linked Git control",
                checkout,
                managedParent,
                data,
                snapshot(caseRoot, data));
    }

    private static void rejectAnalysisOwnedImmediateParent(Path scenarios, UserPrincipal analysis) throws Exception {
        Path caseRoot = Files.createDirectories(scenarios.resolve("replaceable-immediate-parent"));
        Path replaceableParent = Files.createDirectories(caseRoot.resolve("analysis-owned-parent"));
        Files.setOwner(replaceableParent, analysis);
        setMode(replaceableParent, 0755);
        Path managedParent = Files.createDirectories(replaceableParent.resolve("managed"));
        Path checkout = createCheckout(managedParent);
        Path data = Files.createDirectories(scenarios.resolve("replaceable-immediate-data"));
        assertAnalysisCanRename(
                managedParent,
                replaceableParent.resolve("managed-renamed"),
                "the analysis-owned immediate parent");

        RepositoryRuntime runtime = runtime("replaceable-immediate", checkout, managedParent);
        runtime.managedCheckout().validateBoundary(checkout);
        List<Snapshot> before = snapshot(caseRoot, data);
        assertRejectedWithoutRepair("analysis-owned immediate parent", runtime, data, before);
    }

    private static void rejectReplaceableHigherAncestor(Path scenarios, UserPrincipal analysis) throws Exception {
        Path caseRoot = Files.createDirectories(scenarios.resolve("replaceable-higher-ancestor"));
        Path replaceableAncestor = Files.createDirectories(caseRoot.resolve("analysis-owned-ancestor"));
        Files.setOwner(replaceableAncestor, analysis);
        setMode(replaceableAncestor, 0755);
        Path intermediate = Files.createDirectories(replaceableAncestor.resolve("intermediate"));
        Path managedParent = Files.createDirectories(intermediate.resolve("managed"));
        Path checkout = createCheckout(managedParent);
        Path data = Files.createDirectories(scenarios.resolve("replaceable-higher-data"));
        assertAnalysisCanRename(
                intermediate,
                replaceableAncestor.resolve("intermediate-renamed"),
                "the higher analysis-owned ancestor");

        RepositoryRuntime runtime = runtime("replaceable-higher", checkout, managedParent);
        runtime.managedCheckout().validateBoundary(checkout);
        List<Snapshot> before = snapshot(caseRoot, data);
        assertRejectedWithoutRepair("replaceable higher ancestor", runtime, data, before);
    }

    private static void allowSafeChain(Path scenarios) throws Exception {
        Path caseRoot = Files.createDirectories(scenarios.resolve("safe-chain"));
        Path applicationParent = Files.createDirectories(caseRoot.resolve("application-parent"));
        setMode(applicationParent, 0755);
        Path managedParent = Files.createDirectories(applicationParent.resolve("managed"));
        Path checkout = createCheckout(managedParent);
        Path data = Files.createDirectories(scenarios.resolve("safe-chain-data"));
        require(runAsAnalysis("/usr/bin/mv", managedParent.toString(),
                        applicationParent.resolve("managed-renamed").toString()) != 0,
                "the safe application-owned parent was replaceable by the analysis UID");

        AtomicInteger starts = new AtomicInteger();
        JdtLsProcessFactory factory = factory(data, starts);
        RepositoryRuntime runtime = runtime("safe-chain", checkout, managedParent);
        Optional<IOException> failure = Optional.empty();
        try {
            factory.launch(checkout, data, smokeClient(), runtime.managedCheckout());
        } catch (IOException exception) {
            failure = Optional.of(exception);
        }
        require(starts.get() == 1, "the safe checkout chain did not reach the process-start boundary");
        require(failure.isPresent()
                        && "security probe reached process start".equals(failure.get().getMessage()),
                "the safe checkout chain failed before the process-start boundary");
    }

    private static void assertRejectedWithoutRepair(
            String scenario,
            Path checkout,
            Path managedParent,
            Path data,
            List<Snapshot> before) throws Exception {
        Files.createDirectories(data);
        RepositoryRuntime runtime = runtime("security-probe", checkout, managedParent);
        runtime.managedCheckout().validateBoundary(checkout);
        assertRejectedWithoutRepair(scenario, runtime, data, before);
    }

    private static void assertRejectedWithoutRepair(
            String scenario,
            RepositoryRuntime runtime,
            Path data,
            List<Snapshot> before) throws Exception {
        AtomicInteger starts = new AtomicInteger();
        JdtLsProcessFactory factory = factory(data, starts);
        Optional<IOException> failure = Optional.empty();
        try {
            factory.launch(runtime.workingTree(), data, smokeClient(), runtime.managedCheckout());
        } catch (IOException exception) {
            failure = Optional.of(exception);
        }
        require(failure.isPresent(), "the " + scenario + " checkout was not rejected");
        assertUnchanged(before);
        require(!Files.exists(data.resolve("configuration"), LinkOption.NOFOLLOW_LINKS),
                "the " + scenario + " checkout created configuration before rejection");
        require(!"security probe reached process start".equals(failure.get().getMessage()),
                "the " + scenario + " checkout was rejected only after process start");
        require(starts.get() == 0, "the " + scenario + " checkout started JDT");
    }

    private static JdtLsProcessFactory factory(Path data, AtomicInteger starts) {
        JdtLsProperties properties = new JdtLsProperties(
                true,
                Path.of("/opt/jdtls"),
                data,
                Path.of("/opt/java/openjdk/bin/java"),
                JdtLsProperties.IsolationMode.LINUX_UID,
                ANALYSIS_UID,
                ANALYSIS_GID,
                Path.of("/home/analysis"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                1,
                Duration.ofMinutes(1),
                Duration.ofMinutes(1),
                "768m");
        return new JdtLsProcessFactory(
                properties,
                command -> {
                    starts.incrementAndGet();
                    throw new IOException("security probe reached process start");
                },
                (client, process) -> {
                    throw new AssertionError("the process-start fixture must not connect to JDT");
                });
    }

    private static JdtLanguageClient smokeClient() {
        return (JdtLanguageClient) Proxy.newProxyInstance(
                JdtLanguageClient.class.getClassLoader(),
                new Class<?>[] {JdtLanguageClient.class},
                (proxy, method, arguments) -> null);
    }

    private static RepositoryRuntime runtime(String id, Path checkout, Path managedParent) {
        return new RepositoryRuntime(
                RepositoryId.of(id),
                id,
                checkout,
                "file:///unused/security-probe.git",
                "main",
                managedParent);
    }

    private static Path createCheckout(Path managedParent) throws Exception {
        Path checkout = managedParent.resolve("checkout");
        Files.createDirectories(checkout);
        try (Git git = Git.init().setInitialBranch("main").setDirectory(checkout.toFile()).call()) {
            git.getRepository().getConfig().setString("remote", "origin", "url",
                    "file:///unused/security-probe.git");
            git.getRepository().getConfig().save();
        }
        return checkout;
    }

    private static void makeAnalysisWritable(Path gitDirectory, UserPrincipal analysis) throws IOException {
        try (Stream<Path> entries = Files.walk(gitDirectory)) {
            for (Path entry : entries.toList()) {
                Files.setOwner(entry, analysis);
                setMode(entry, Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) ? 0777 : 0666);
            }
        }
    }

    private static void setMode(Path path, int mode) throws IOException {
        Files.setAttribute(path, "unix:mode", mode, LinkOption.NOFOLLOW_LINKS);
    }

    private static void assertAnalysisCanRename(Path entry, Path renamed, String description) throws Exception {
        require(runAsAnalysis("/usr/bin/mv", entry.toString(), renamed.toString()) == 0,
                "the configured analysis UID cannot replace " + description);
        require(runAsAnalysis("/usr/bin/mv", renamed.toString(), entry.toString()) == 0,
                "the test could not restore " + description);
    }

    private static int runAsAnalysis(String... command) throws Exception {
        List<String> invocation = new ArrayList<>(List.of(
                "/usr/bin/setpriv",
                "--reuid=10001",
                "--regid=10001",
                "--clear-groups",
                "--no-new-privs",
                "--bounding-set=-all"));
        invocation.addAll(Arrays.asList(command));
        Process process = new ProcessBuilder(invocation).redirectErrorStream(true).start();
        process.getInputStream().transferTo(OutputStream.nullOutputStream());
        return process.waitFor();
    }

    private static List<Snapshot> snapshot(Path... roots) throws IOException {
        List<Snapshot> snapshots = new ArrayList<>();
        for (Path root : roots) {
            if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                try (Stream<Path> entries = Files.walk(root)) {
                    for (Path entry : entries.toList()) {
                        snapshots.add(snapshot(entry));
                    }
                }
            } else {
                snapshots.add(snapshot(root));
            }
        }
        return List.copyOf(snapshots);
    }

    private static Snapshot snapshot(Path path) throws IOException {
        boolean regularFile = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        byte[] contents = regularFile ? Files.readAllBytes(path) : new byte[0];
        long uid = ((Number) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue();
        int mode = ((Number) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue();
        return new Snapshot(
                path,
                Files.getOwner(path, LinkOption.NOFOLLOW_LINKS),
                uid,
                mode,
                regularFile,
                contents);
    }

    private static void assertUnchanged(List<Snapshot> snapshots) throws IOException {
        for (Snapshot before : snapshots) {
            Path path = before.path();
            require(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(before.owner()),
                    "ownership changed before rejecting " + path);
            long uid = ((Number) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue();
            require(uid == before.uid(), "UID changed before rejecting " + path);
            int mode = ((Number) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue();
            require(mode == before.mode(), "mode changed before rejecting " + path);
            if (before.regularFile()) {
                require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
                        "file type changed before rejecting " + path);
                require(Arrays.equals(Files.readAllBytes(path), before.contents()),
                        "content changed before rejecting " + path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record Snapshot(
            Path path,
            UserPrincipal owner,
            long uid,
            int mode,
            boolean regularFile,
            byte[] contents) {
    }
}
JAVA
  classpath="$probe/classes:$probe/app/BOOT-INF/classes:$probe/app/BOOT-INF/lib/*"
  /opt/java/openjdk/bin/javac -cp "$classpath" -d "$probe/classes" "$probe/CheckoutSecurityProbe.java"
  /opt/java/openjdk/bin/java -cp "$classpath" com.java.semantic.semantic.adapter.jdtls.CheckoutSecurityProbe "$probe/scenarios"
'
docker run --rm --env SYNTHETIC_PARENT_SECRET=only-for-image-smoke --entrypoint sh "${image_name}" -ceu '
  env -i HOME=/home/analysis USER=analysis \
    /usr/bin/setpriv --reuid=10001 --regid=10001 --clear-groups --no-new-privs --bounding-set=-all \
    sh -ceu '"'"'
      test "$(id -u)" = 10001
      test "$(id -g)" = 10001
      test "$(awk "/^CapEff:/ { print \$2 }" /proc/self/status)" = 0000000000000000
      test -z "${SYNTHETIC_PARENT_SECRET:-}"
    '"'"'
'

docker run --rm --env SYNTHETIC_PARENT_SECRET=only-for-image-smoke --entrypoint sh "${image_name}" -ceu '
  workspace=/data/jdtls/smoke
  mkdir -p "$workspace"
  chown 10001:10001 "$workspace"
  cp -a /opt/jdtls/config_linux "$workspace/configuration"
  chown -R 10001:10001 "$workspace"
  launcher=$(find /opt/jdtls/plugins -name "org.eclipse.equinox.launcher_*.jar" -print -quit)
  initialize="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"processId\":null,\"rootUri\":\"file:///tmp\",\"capabilities\":{}}}"
  initialized="{\"jsonrpc\":\"2.0\",\"method\":\"initialized\",\"params\":{}}"
  shutdown="{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"shutdown\",\"params\":null}"
  exit_request="{\"jsonrpc\":\"2.0\",\"method\":\"exit\",\"params\":null}"
  send() { printf "Content-Length: %s\\r\\n\\r\\n%s" "${#1}" "$1"; }
  response=$( (
    send "$initialize"
    send "$initialized"
    send "$shutdown"
    send "$exit_request"
  ) | env -i HOME=/home/analysis USER=analysis \
    /usr/bin/setpriv --reuid=10001 --regid=10001 --clear-groups --no-new-privs --bounding-set=-all \
    /opt/java/openjdk/bin/java \
      -Declipse.application=org.eclipse.jdt.ls.core.id1 \
      -Dosgi.bundles.defaultStartLevel=4 \
      -Declipse.product=org.eclipse.jdt.ls.core.product \
      -Dlog.level=ALL \
      -javaagent:/opt/jdtls/lombok.jar \
      --add-modules=ALL-SYSTEM \
      --add-opens java.base/java.util=ALL-UNNAMED \
      --add-opens java.base/java.lang=ALL-UNNAMED \
      -jar "$launcher" -configuration "$workspace/configuration" -data "$workspace")
  printf "%s" "$response" | grep -q "\"id\":1"
  printf "%s" "$response" | grep -q "\"id\":2"
'
