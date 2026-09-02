package com.java.semantic.model.codefact;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TypeMemberQueryTest {
    @Test
    void accepts_all_supported_member_kinds_in_one_paged_query() {
        assertDoesNotThrow(() -> new TypeMemberQuery(new RepositoryId("orders"),
                new RepositoryRevision("a".repeat(40)), new SourceTypeIdentity(new JavaTypeIdentity("example", "PaymentMethod"),
                "src/main/java/example/PaymentMethod.java"), TypeMemberQuery.MEMBER_KINDS, 0, 20));
    }
}
