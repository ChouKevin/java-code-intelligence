package com.java.semantic.model.codefact;

import com.java.semantic.model.support.ModelValidation;

import java.util.Objects;

public record JavaTypeIdentity(String packageName, String className) implements CanonicalIdentity {

    public JavaTypeIdentity {
        packageName = Objects.requireNonNull(packageName, "package name is required").trim();
        className = JavaIdentityNormalizer.className(packageName, className);
        ModelValidation.require(!className.isBlank(), "class name must not be blank");
        ModelValidation.require(className.length() <= 255, "class name must not exceed 255 characters");
        for (String segment : className.split("\\.", -1)) {
            ModelValidation.require(!segment.isBlank() && Character.isJavaIdentifierStart(segment.codePointAt(0))
                            && segment.codePoints().skip(1).allMatch(Character::isJavaIdentifierPart)
                            && segment.codePoints().noneMatch(Character::isIdentifierIgnorable),
                    "class name must contain Java identifier segments");
        }
    }

    public String fullyQualifiedName() {
        return packageName.isBlank() ? className : packageName + "." + className;
    }

    @Override
    public String canonicalForm() {
        return fullyQualifiedName();
    }
}
