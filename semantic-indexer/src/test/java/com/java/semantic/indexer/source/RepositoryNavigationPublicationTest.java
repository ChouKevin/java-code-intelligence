package com.java.semantic.indexer.source;

import com.java.semantic.indexer.job.FileSourceJobStore;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.model.source.RepositoryNavigationHint;
import com.java.semantic.model.source.SourceRepositoryDescriptor;
import com.java.semantic.repository.config.RepositoryProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryNavigationPublicationTest {
    @TempDir Path root;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void binding_distinguishes_absence_draft_approval_and_present_without_status() {
        RepositoryProperties absent = bind(Map.of());
        assertThat(absent.getRepositories().get("orders").getNavigationHint()).isNull();
        assertThat(new RepositoryRegistry(absent).descriptors().getFirst().navigationHint()).isEmpty();
        RepositoryProperties draft = bind(Map.of("review-status", "DRAFT", "summary", "\uD800"));
        assertThat(new RepositoryRegistry(draft).descriptors().getFirst().navigationHint()).isEmpty();
        RepositoryProperties approved = bind(Map.of("review-status", "APPROVED", "summary", "會員規則🙂",
                "keywords[0]", "VIP", "keywords[1]", "VIP", "analyzed-revision", "a".repeat(40)));
        RepositoryNavigationHint hint = new RepositoryRegistry(approved).descriptors().getFirst().navigationHint().orElseThrow();
        assertThat(hint.summary()).isEqualTo("會員規則🙂");
        assertThat(hint.keywords()).containsExactly("VIP");
        for (Map<String, String> settings : List.of(Map.of("summary", "not reviewed"),
                Map.of("review-status", ""), Map.of("review-status", " "), Map.of("review-status", "UNKNOWN"),
                Map.of("review-status", "APPROVED", "summary", "rules", "analyzed-revision", "a".repeat(40)))) {
            RepositoryProperties invalid = bind(settings);
            assertThat(invalid.getRepositories().get("orders").getNavigationHint()).isNotNull();
            assertThatThrownBy(() -> new RepositoryRegistry(invalid)).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void approval_update_and_retraction_preserve_sealed_source_and_lifecycle_bytes() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root);
                DurableSourceFiles owner = new DurableSourceFiles(fixture.admin)) {
            String revision = fixture.commit("A.java", "class A { // 語言🙂\r\n}\r\n".getBytes(StandardCharsets.UTF_8), "A");
            RepositoryProperties properties = SourcePreparationPublicationTest.properties(fixture);
            RepositoryRegistry registry = new RepositoryRegistry(properties);
            FileSourceJobStore jobs = new FileSourceJobStore(properties, mapper, owner);
            SourcePublicationStore publications = SourceLifecycleFixture.publications(properties, mapper, owner, jobs);
            RepositoryRevisionResolver resolver = new RepositoryRevisionResolver(registry, properties);
            RepositorySourceManager manager = new RepositorySourceManager(jobs, resolver,
                    new JGitRevisionExporter(resolver, registry, mapper), publications, registry, properties);
            new SourcePreparationService(jobs, publications, registry).prepareSource(fixture.repository,
                    new PreparationRequestId(UUID.randomUUID().toString()), Optional.empty());
            manager.execute(jobs.claimNext().orElseThrow());
            Map<Path, byte[]> sourceBytes = snapshot(fixture.published.resolve("orders"));
            RepositoryProperties.RepositoryConfig config = properties.getRepositories().get("orders");
            config.setNavigationHint(approved("會員 \"規則\" \\ 🙂", List.of("VIP", "會員", "VIP"), "b".repeat(40)));
            publish(properties, owner);
            SourceRepositoryDescriptor descriptor = read(fixture.published).getFirst();
            assertThat(descriptor.navigationHint().orElseThrow().keywords()).containsExactly("VIP", "會員");
            assertThat(descriptor.navigationHint().orElseThrow().analyzedRevision()).isEqualTo("b".repeat(40));
            assertThat(publications.state(fixture.repository).current().orElseThrow().revision()).isEqualTo(revision);
            RepositoryProperties.NavigationHintConfig draft = new RepositoryProperties.NavigationHintConfig();
            draft.setReviewStatus("DRAFT");
            draft.setSummary("not published");
            config.setNavigationHint(draft);
            publish(properties, owner);
            assertThat(read(fixture.published).getFirst().navigationHint()).isEmpty();
            assertThat(Files.readString(fixture.published.resolve("repositories.json"))).doesNotContain("not published");
            config.setNavigationHint(approved("updated", List.of("rules"), revision));
            publish(properties, owner);
            assertThat(read(fixture.published).getFirst().navigationHint().orElseThrow().summary()).isEqualTo("updated");
            config.setNavigationHint(null);
            publish(properties, owner);
            assertThat(read(fixture.published).getFirst().navigationHint()).isEmpty();
            Map<Path, byte[]> after = snapshot(fixture.published.resolve("orders"));
            assertThat(after.keySet()).isEqualTo(sourceBytes.keySet());
            for (Path file : sourceBytes.keySet()) assertThat(after.get(file)).isEqualTo(sourceBytes.get(file));
        }
    }

    @Test
    void invalid_late_hint_and_serialized_hint_overflow_preserve_registry_and_do_not_initialize_new_repo() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root);
                DurableSourceFiles owner = new DurableSourceFiles(fixture.admin)) {
            RepositoryProperties properties = SourcePreparationPublicationTest.properties(fixture);
            publish(properties, owner);
            byte[] prior = Files.readAllBytes(fixture.published.resolve("repositories.json"));
            Map<Path, byte[]> lifecycle = snapshot(fixture.published.resolve("orders"));
            RepositoryProperties.RepositoryConfig late = new RepositoryProperties.RepositoryConfig();
            late.setUrl(fixture.remote.toUri().toString());
            late.setDisplayName("Late");
            LinkedHashMap<String, RepositoryProperties.RepositoryConfig> configured = new LinkedHashMap<>(properties.getRepositories());
            configured.put("zz-late", late);
            properties.setRepositories(configured);
            for (String invalid : List.of("\uD800", "line\u2028separator", "line\u2029separator", "x".repeat(241))) {
                late.setNavigationHint(approved(invalid, List.of("rules"), "a".repeat(40)));
                assertThatThrownBy(() -> publish(properties, owner)).isInstanceOf(IllegalArgumentException.class);
                assertUnchanged(fixture, prior, lifecycle);
            }
            RepositoryProperties.NavigationHintConfig missing = new RepositoryProperties.NavigationHintConfig();
            missing.setSummary("not reviewed");
            late.setNavigationHint(missing);
            assertThatThrownBy(() -> publish(properties, owner)).isInstanceOf(IllegalArgumentException.class);
            assertUnchanged(fixture, prior, lifecycle);
            List<String> keywords = java.util.stream.IntStream.range(0, 12)
                    .mapToObj(index -> "🙂".repeat(30) + String.format("%02d", index)).toList();
            late.setNavigationHint(approved("界".repeat(240), keywords, "a".repeat(40)));
            assertThatThrownBy(() -> publish(properties, owner)).isInstanceOf(IllegalStateException.class);
            assertUnchanged(fixture, prior, lifecycle);
            assertThat(Files.exists(fixture.admin.resolve("repositories/zz-late"))).isFalse();
        }
    }

    @Test
    void old_registry_without_hint_is_readable_with_the_publication_mapper() {
        SourceRepositoryDescriptor[] descriptors = mapper.readValue("""
                [{"repositoryId":"orders","displayName":"Orders","defaultBranch":"main","projectGuidePath":null}]
                """, SourceRepositoryDescriptor[].class);
        assertThat(descriptors[0].navigationHint()).isEmpty();
    }

    private RepositoryProperties bind(Map<String, String> hint) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("semantic.source-admin-root", root.resolve("admin").toString());
        settings.put("semantic.source-published-root", root.resolve("public").toString());
        settings.put("semantic.repositories.orders.url", root.resolve("remote").toUri().toString());
        settings.put("semantic.repositories.orders.display-name", "Orders");
        hint.forEach((key, value) -> settings.put("semantic.repositories.orders.navigation-hint." + key, value));
        return new Binder(new MapConfigurationPropertySource(settings)).bind("semantic", Bindable.of(RepositoryProperties.class)).get();
    }

    private static RepositoryProperties.NavigationHintConfig approved(String summary, List<String> keywords, String revision) {
        RepositoryProperties.NavigationHintConfig hint = new RepositoryProperties.NavigationHintConfig();
        hint.setReviewStatus("APPROVED");
        hint.setSummary(summary);
        hint.setKeywords(keywords);
        hint.setAnalyzedRevision(revision);
        return hint;
    }

    private void publish(RepositoryProperties properties, DurableSourceFiles owner) {
        SourceLifecycleFixture.publisher(new RepositoryRegistry(properties), properties, mapper, owner).publish();
    }

    private List<SourceRepositoryDescriptor> read(Path published) throws Exception {
        return List.of(mapper.readValue(Files.readAllBytes(published.resolve("repositories.json")), SourceRepositoryDescriptor[].class));
    }

    private void assertUnchanged(LocalSourceFixture fixture, byte[] prior, Map<Path, byte[]> lifecycle) throws Exception {
        assertThat(Files.readAllBytes(fixture.published.resolve("repositories.json"))).isEqualTo(prior);
        assertThat(Files.exists(fixture.published.resolve("zz-late"))).isFalse();
        Map<Path, byte[]> after = snapshot(fixture.published.resolve("orders"));
        assertThat(after.keySet()).isEqualTo(lifecycle.keySet());
        for (Path file : lifecycle.keySet()) assertThat(after.get(file)).isEqualTo(lifecycle.get(file));
    }

    private static Map<Path, byte[]> snapshot(Path directory) throws Exception {
        Map<Path, byte[]> bytes = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) bytes.put(file, Files.readAllBytes(file));
        }
        return bytes;
    }
}
