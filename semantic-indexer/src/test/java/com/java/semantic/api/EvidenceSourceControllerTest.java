package com.java.semantic.api;

import com.java.semantic.api.security.ApiTokenFilter;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.application.EvidenceSourceApplicationService;
import com.java.semantic.syntax.application.EvidenceSourceQuery;
import com.java.semantic.syntax.application.EvidenceSourceResult;
import com.java.semantic.syntax.domain.MapperEvidenceRepresentation;
import com.java.semantic.syntax.domain.MapperFragmentIdentity;
import com.java.semantic.syntax.domain.MapperStatementIdentity;
import com.java.semantic.syntax.domain.MapperStatementKey;
import com.java.semantic.syntax.domain.SourceRange;
import com.java.semantic.syntax.domain.SourceRangeSegment;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import tools.jackson.databind.ObjectMapper;
import com.java.semantic.api.dto.EvidenceSourceRequest;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** typed evidence source HTTP 契約測試 */
@SpringBootTest(properties = {
        "semantic.api.api-token=test-token",
        "semantic.repositories.orders.url=https://example.invalid/orders.git"
})
@AutoConfigureMockMvc
class EvidenceSourceControllerTest {

    private static final String REVISION = "1".repeat(40);
    private static final String SOURCE_FILE = "src/main/java/com/example/OrderMapper.java";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Validator validator;

    @MockitoBean
    private EvidenceSourceApplicationService applicationService;

    @Test
    void should_read_annotation_sql_by_typed_identity_and_return_a_bounded_source_segment() throws Exception {
        MapperStatementIdentity identity = annotationIdentity();
        SourceRange location = new SourceRange(SOURCE_FILE, range(8, 4, 8, 41));
        SourceRangeSegment segment = new SourceRangeSegment(
                location,
                "@Select(\"select * from orders\")",
                Optional.of(new SourceRange(SOURCE_FILE, range(8, 41, 8, 42))),
                false);
        given(applicationService.read(any())).willReturn(new EvidenceSourceResult(
                RepositoryId.of("orders"),
                RepositoryRevision.ofSha(REVISION),
                new EvidenceSourceQuery.AnnotationSql(identity),
                location,
                segment));

        mockMvc.perform(post("/v1/discovery/evidence-source")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, "test-token")
                        .contentType(APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repoId").value("orders"))
                .andExpect(jsonPath("$.analyzedRevision").value(REVISION))
                .andExpect(jsonPath("$.identity.kind").value("ANNOTATION_SQL"))
                .andExpect(jsonPath("$.identity.statementIdentity.representation").value("ANNOTATION_SQL_TEXT"))
                .andExpect(jsonPath("$.location.sourceFile").value(SOURCE_FILE))
                .andExpect(jsonPath("$.location.range.start.line").value(8))
                .andExpect(jsonPath("$.segment.content").value("@Select(\"select * from orders\")"))
                .andExpect(jsonPath("$.segment.nextLocation.range.start.character").value(41))
                .andExpect(jsonPath("$.availableFollowUps[0].operation").value("GET_SOURCE_SEGMENT"))
                .andExpect(jsonPath("$.availableFollowUps[0].api.path").value("/v1/discovery/source-segment"))
                .andExpect(jsonPath("$.availableFollowUps[0].request.contextLines").value(0));

        ArgumentCaptor<EvidenceSourceQuery> query = ArgumentCaptor.forClass(EvidenceSourceQuery.class);
        then(applicationService).should().read(query.capture());
        assertThat(query.getValue()).isEqualTo(new EvidenceSourceQuery(
                RepositoryId.of("orders"),
                RepositoryRevision.ofSha(REVISION),
                new EvidenceSourceQuery.AnnotationSql(identity)));
    }

    @ParameterizedTest
    @MethodSource("mapperEvidenceCases")
    void should_map_mapper_evidence_identities_and_preserve_exact_xml(MapperEvidenceCase evidenceCase)
            throws Exception {
        SourceRangeSegment segment = new SourceRangeSegment(
                evidenceCase.location(), evidenceCase.content(), Optional.empty(), false);
        given(applicationService.read(any())).willReturn(new EvidenceSourceResult(
                RepositoryId.of("orders"),
                RepositoryRevision.ofSha(REVISION),
                evidenceCase.identity(),
                evidenceCase.location(),
                segment));

        mockMvc.perform(post("/v1/discovery/evidence-source")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, "test-token")
                        .contentType(APPLICATION_JSON)
                        .content(evidenceCase.request()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity.kind").value(evidenceCase.kind()))
                .andExpect(jsonPath("$.location.sourceFile").value(evidenceCase.location().sourceFile()))
                .andExpect(jsonPath("$.location.range.start.line")
                        .value(evidenceCase.location().range().start().line()))
                .andExpect(jsonPath("$.location.range.start.character")
                        .value(evidenceCase.location().range().start().character()))
                .andExpect(jsonPath("$.location.range.end.line")
                        .value(evidenceCase.location().range().end().line()))
                .andExpect(jsonPath("$.location.range.end.character")
                        .value(evidenceCase.location().range().end().character()))
                .andExpect(jsonPath("$.segment.content").value(evidenceCase.content()));

        ArgumentCaptor<EvidenceSourceQuery> query = ArgumentCaptor.forClass(EvidenceSourceQuery.class);
        then(applicationService).should().read(query.capture());
        assertThat(query.getValue()).isEqualTo(new EvidenceSourceQuery(
                RepositoryId.of("orders"), RepositoryRevision.ofSha(REVISION), evidenceCase.identity()));
    }

    @Test
    void should_reject_unknown_identity_fields() throws Exception {
        mockMvc.perform(post("/v1/discovery/evidence-source")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, "test-token")
                        .contentType(APPLICATION_JSON)
                        .content(validRequest().replace(
                                "\"kind\":\"ANNOTATION_SQL\"",
                                "\"kind\":\"ANNOTATION_SQL\",\"unexpected\":\"forbidden\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));

        then(applicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_deserialize_an_identity_with_omitted_non_matching_union_members() throws Exception {
        EvidenceSourceRequest request = objectMapper.readValue(validRequest(), EvidenceSourceRequest.class);

        assertThat(request.identity().statementIdentity()).isPresent();
        assertThat(request.identity().fragmentIdentity()).isEmpty();
        assertThat(validator.validate(request)).isEmpty();
    }

    private static MapperStatementIdentity annotationIdentity() {
        return new MapperStatementIdentity(
                new MapperStatementKey("com.example.OrderMapper", "find"),
                SOURCE_FILE,
                Optional.empty(),
                0,
                MapperEvidenceRepresentation.ANNOTATION_SQL_TEXT);
    }

    private static Stream<Arguments> mapperEvidenceCases() {
        String resourcePath = "src/main/resources/com/example/OrderMapper.xml";
        MapperStatementIdentity statement = new MapperStatementIdentity(
                new MapperStatementKey("com.example.OrderMapper", "find"),
                resourcePath,
                Optional.empty(),
                0,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        MapperFragmentIdentity fragment = new MapperFragmentIdentity(
                "com.example.OrderMapper",
                "columns",
                resourcePath,
                1,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        String dynamicStatement = """
                <select id="find">
                  SELECT * FROM orders
                  <where>
                    <if test="id > 0">id = #{id}</if>
                  </where>
                </select>""";
        String fragmentContent = "<sql id=\"columns\">id, name</sql>";
        return Stream.of(
                Arguments.of(new MapperEvidenceCase(
                        "MAPPER_STATEMENT",
                        new EvidenceSourceQuery.MapperStatement(statement),
                        new SourceRange(resourcePath, range(4, 2, 9, 11)),
                        dynamicStatement,
                        mapperStatementRequest())),
                Arguments.of(new MapperEvidenceCase(
                        "MAPPER_FRAGMENT",
                        new EvidenceSourceQuery.MapperFragment(fragment),
                        new SourceRange(resourcePath, range(11, 2, 11, 2 + fragmentContent.length())),
                        fragmentContent,
                        mapperFragmentRequest())));
    }

    private static String validRequest() {
        return """
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "identity":{
                    "kind":"ANNOTATION_SQL",
                    "statementIdentity":{
                      "statementKey":{"namespace":"com.example.OrderMapper","statementId":"find"},
                      "resourcePath":"src/main/java/com/example/OrderMapper.java",
                      "documentOrdinal":0,
                      "representation":"ANNOTATION_SQL_TEXT"
                    }
                  }
                }
                """;
    }

    private static String mapperStatementRequest() {
        return """
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "identity":{
                    "kind":"MAPPER_STATEMENT",
                    "statementIdentity":{
                      "statementKey":{"namespace":"com.example.OrderMapper","statementId":"find"},
                      "resourcePath":"src/main/resources/com/example/OrderMapper.xml",
                      "documentOrdinal":0,
                      "representation":"MAPPER_XML_ELEMENT"
                    }
                  }
                }
                """;
    }

    private static String mapperFragmentRequest() {
        return """
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "identity":{
                    "kind":"MAPPER_FRAGMENT",
                    "fragmentIdentity":{
                      "namespace":"com.example.OrderMapper",
                      "fragmentId":"columns",
                      "resourcePath":"src/main/resources/com/example/OrderMapper.xml",
                      "documentOrdinal":1,
                      "representation":"MAPPER_XML_ELEMENT"
                    }
                  }
                }
                """;
    }

    private static SyntaxRange range(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new SyntaxRange(
                new SyntaxPosition(startLine, startCharacter),
                new SyntaxPosition(endLine, endCharacter));
    }

    private record MapperEvidenceCase(
            String kind,
            EvidenceSourceQuery.EvidenceIdentity identity,
            SourceRange location,
            String content,
            String request) {
    }
}
