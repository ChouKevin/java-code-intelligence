package com.java.semantic.repository.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedDisposableCheckoutTest {
    @Test
    void strict_validation_rejects_a_descendant_symlink(@TempDir Path temporaryDirectory) throws IOException {
        Path managedParent = Files.createDirectories(temporaryDirectory.resolve("managed"));
        Path checkoutRoot = Files.createDirectories(managedParent.resolve("checkout"));
        Path target = Files.createDirectories(temporaryDirectory.resolve("target"));
        Files.createSymbolicLink(checkoutRoot.resolve("tracked-link"), target);

        ManagedDisposableCheckout checkout = new ManagedDisposableCheckout(checkoutRoot, managedParent);

        assertThatThrownBy(() -> checkout.validate(checkoutRoot)).isInstanceOf(IOException.class);
    }
}
