package com.example.evidence;

import static com.example.evidence.Audit.record;
import static com.example.missing.RecoveredAudit.*;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

@Qualifier("fastOrderService")
@Primary
class FastOrderService {
}

class OrderCoordinator {

    @Qualifier("vaultMapper")
    private final OrderMapper mapper = new VaultMapper();

    private MissingDependency unresolved;

    private List<String> libraryValues;

    Receipt<OrderLine> process(OrderLine order) {
        Receipt<OrderLine> receipt = mapper.save(order);
        Receipt<OrderLine> repeated = mapper.save(order);
        OrderLine created = new OrderLine();
        Runnable lambda = () -> mapper.save(order);
        Function<OrderLine, Receipt<OrderLine>> reference = mapper::save;
        Function<String, OrderLine> constructor = OrderLine::new;
        Function<OrderLine, String> typeReference = OrderLine::value;
        record("saved");
        recoveredRecord("unresolved");
        return receipt;
    }
}

class ParentEvidence {

    String inherited() {
        return "parent";
    }
}

class OuterEvidence extends ParentEvidence {

    class Inner {

        Supplier<String> qualifiedSuperReference() {
            return OuterEvidence.super::inherited;
        }
    }
}

class VaultMapper implements OrderMapper {

    @Override
    public Receipt<OrderLine> save(OrderLine order) {
        return new Receipt<>();
    }
}

interface OrderMapper {

    Receipt<OrderLine> save(OrderLine order);
}

record Receipt<T>() {
}

record OrderLine(String value) {
}

final class Audit {

    private Audit() {
    }

    static void record(String value) {
    }
}

@interface Primary {
}

@interface Qualifier {

    String value();
}

@interface BodyEvidenceMarker {

    Class<?> value();
}

class BodyTypeEvidenceFixture {

    private SecretHolder holder;

    private GenericSecretHolder genericHolder;

    private ForbiddenBareFieldValue bareValue;

    @BodyEvidenceMarker(ForbiddenAnnotationMember.class)
    void inspect(Object candidate) {
        // LOCAL_DECLARATION_SENTINEL
        ForbiddenLocal local;
        // CAST_SENTINEL
        Object cast = (ForbiddenCast) candidate;
        // INSTANCEOF_SENTINEL
        boolean matching = candidate instanceof ForbiddenInstanceof;
        // CLASS_LITERAL_SENTINEL
        Class<?> literal = ForbiddenClassLiteral.class;
        // FIELD_ACCESS_SENTINEL
        Object fieldValue = holder.value;
        // GENERIC_FIELD_ACCESS_SENTINEL
        Object genericFieldValue = genericHolder.values;
        // BARE_FIELD_ACCESS_SENTINEL
        Object directFieldValue = bareValue;
        String readable = "READABLE_BODY_EVIDENCE_SIBLING";
    }
}

class SecretHolder {

    ForbiddenFieldValue value;
}

class GenericSecretHolder {

    List<ForbiddenGenericFieldValue> values;
}

class ForbiddenLocal {
}

class ForbiddenCast {
}

class ForbiddenInstanceof {
}

class ForbiddenClassLiteral {
}

class ForbiddenFieldValue {
}

class ForbiddenGenericFieldValue {
}

class ForbiddenBareFieldValue {
}

class ForbiddenAnnotationMember {
}
