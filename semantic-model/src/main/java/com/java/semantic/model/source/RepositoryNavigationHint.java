package com.java.semantic.model.source;

import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.support.ModelValidation;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Human-reviewed navigation only; never evidence of source availability or correctness. */
public record RepositoryNavigationHint(String summary, List<String> keywords,
        String analyzedRevision, ReviewStatus reviewStatus, Trust trust) {

    public enum ReviewStatus { APPROVED }
    public enum Trust { NOT_VERIFIED }

    public RepositoryNavigationHint {
        summary = singleLine(summary, "navigation summary", 240);
        Objects.requireNonNull(keywords, "navigation keywords");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String keyword : keywords) {
            unique.add(singleLine(keyword, "navigation keyword", 32));
        }
        ModelValidation.require(!unique.isEmpty() && unique.size() <= 12,
                "navigation keywords must contain between 1 and 12 distinct entries");
        keywords = List.copyOf(unique);
        analyzedRevision = RepositoryRevision.ofSha(analyzedRevision).value();
        reviewStatus = Objects.requireNonNull(reviewStatus, "navigation review status");
        trust = Objects.requireNonNull(trust, "navigation trust");
    }

    private static String singleLine(String value, String name, int maximum) {
        String text = ModelValidation.requiredText(value, name);
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isHighSurrogate(character)) {
                ModelValidation.require(index + 1 < text.length()
                                && Character.isLowSurrogate(text.charAt(index + 1)),
                        name + " must contain valid Unicode");
                index++;
            } else {
                ModelValidation.require(!Character.isLowSurrogate(character), name + " must contain valid Unicode");
            }
        }
        ModelValidation.require(text.codePointCount(0, text.length()) <= maximum,
                name + " exceeds its code point limit");
        ModelValidation.require(text.codePoints().noneMatch(codePoint -> Character.isISOControl(codePoint)
                        || codePoint == 0x2028 || codePoint == 0x2029),
                name + " must be single-line text without controls");
        return text;
    }
}
