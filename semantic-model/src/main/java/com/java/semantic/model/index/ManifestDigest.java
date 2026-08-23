package com.java.semantic.model.index;

import com.java.semantic.model.support.ModelValidation;

public record ManifestDigest(String value) {

    public ManifestDigest {
        value = ModelValidation.sha256(value, "manifest digest");
    }
}
