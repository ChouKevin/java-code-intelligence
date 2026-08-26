package com.java.semantic.indexer.uat;

import com.java.semantic.indexer.api.IndexRepositoryController;
import com.java.semantic.indexer.api.IndexerApiExceptionHandler;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobAlreadyActiveException;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.model.repository.RepositoryId;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UatIndexControllerTest {
    @Test
    void controller_is_absent_without_the_uat_profile() {
        new ApplicationContextRunner()
                .withUserConfiguration(UatIndexController.class)
                .run(context -> assertThat(context).doesNotHaveBean(UatIndexController.class));
    }

    @Test
    void reset_admits_an_accepted_targetless_reset_job() {
        UatRepositoryResetService resets = mock(UatRepositoryResetService.class);
        UatPublicationGate gate = new UatPublicationGate(Duration.ofSeconds(1));
        IndexJob reset = new IndexJob(new IndexJobId("reset-1"), RepositoryId.of("payment"), Optional.empty(),
                IndexJobPhase.ACCEPTED, true, Optional.empty(), false, IndexJobOperation.RESET);
        when(resets.admit(RepositoryId.of("payment"))).thenReturn(reset);

        org.springframework.http.ResponseEntity<IndexRepositoryController.IndexJobResponse> response =
                new UatIndexController(gate, resets).reset("payment");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().target()).isNull(); // cs-allow: RESET deliberately has no target
        assertThat(response.getBody().phase()).isEqualTo("ACCEPTED");
    }

    @Test
    void active_reset_returns_the_typed_conflict_body() throws Exception {
        UatRepositoryResetService resets = mock(UatRepositoryResetService.class);
        when(resets.admit(RepositoryId.of("payment")))
                .thenThrow(new IndexJobAlreadyActiveException(RepositoryId.of("payment")));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new UatIndexController(
                new UatPublicationGate(Duration.ofSeconds(1)), resets))
                .setControllerAdvice(new IndexerApiExceptionHandler()).build();

        mvc.perform(post("/index/uat/repositories/payment/reset"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REPOSITORY_ACTIVE"))
                .andExpect(jsonPath("$.repoId").value("payment"))
                .andExpect(jsonPath("$.candidates").isArray())
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void reset_rejection_is_a_bad_request_and_active_publication_arm_is_a_conflict() {
        UatRepositoryResetService resets = mock(UatRepositoryResetService.class);
        UatPublicationGate gate = new UatPublicationGate(Duration.ofSeconds(1));
        UatIndexController controller = new UatIndexController(gate, resets);
        when(resets.admit(RepositoryId.of("payment"))).thenThrow(new IllegalArgumentException("not configured"));

        assertThatThrownBy(() -> controller.reset("payment"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThatThrownBy(() -> controller.reset("../payment"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        gate.arm();
        assertThatThrownBy(controller::armPublication)
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
        gate.release();
    }
}
