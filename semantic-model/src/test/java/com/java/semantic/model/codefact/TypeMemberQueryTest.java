package com.java.semantic.model.codefact;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TypeMemberQueryTest {
    @Test
    void rejects_mixed_enum_and_ordinary_member_paging_kinds() {
        assertThrows(IllegalArgumentException.class, () -> new TypeMemberQuery(new RepositoryId("orders"),
                new RepositoryRevision("a".repeat(40)), new SourceTypeIdentity(new JavaTypeIdentity("example", "PaymentMethod"),
                "src/main/java/example/PaymentMethod.java"), Set.of(CodeFactKind.ENUM_CONSTANT, CodeFactKind.METHOD), 0, 20));
    }
}
