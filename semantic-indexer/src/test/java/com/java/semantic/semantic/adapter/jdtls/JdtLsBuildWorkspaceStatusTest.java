package com.java.semantic.semantic.adapter.jdtls;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdtLsBuildWorkspaceStatusTest {

    @Test
    void should_match_jdt_ls_wire_ordinals() {
        assertThat(JdtLsBuildWorkspaceStatus.FAILED.ordinal()).isZero();
        assertThat(JdtLsBuildWorkspaceStatus.SUCCEED.ordinal()).isEqualTo(1);
        assertThat(JdtLsBuildWorkspaceStatus.WITH_ERROR.ordinal()).isEqualTo(2);
        assertThat(JdtLsBuildWorkspaceStatus.CANCELLED.ordinal()).isEqualTo(3);
    }
}
