package com.java.semantic.syntax.adapter.jdt;

import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.TagElement;
import org.eclipse.jdt.core.dom.TextElement;

import java.util.Objects;

/**
 * 讀取 Javadoc 的主描述
 * <p>
 * 只取主描述，@param / @return / @throws 這些 block tag 一律略過
 * 舊分析器用 toText 把所有 block tag 平鋪進描述，對業務語意沒有幫助
 */
final class JavadocReader {

    private JavadocReader() {
    }

    /** 主描述，取不到時回傳空字串 */
    static String descriptionOf(BodyDeclaration declaration) {
        Javadoc javadoc = declaration.getJavadoc();
        if (Objects.isNull(javadoc)) {
            return "";
        }

        StringBuilder text = new StringBuilder();
        for (Object tag : javadoc.tags()) {
            TagElement element = (TagElement) tag;
            if (Objects.nonNull(element.getTagName())) {
                continue;
            }
            appendFragments(element, text);
        }
        return text.toString().replaceAll("\\s+", " ").trim();
    }

    private static void appendFragments(TagElement element, StringBuilder text) {
        for (Object fragment : element.fragments()) {
            if (fragment instanceof TextElement textElement) {
                text.append(textElement.getText()).append(' ');
            } else if (fragment instanceof TagElement nested) {
                appendFragments(nested, text);
            } else if (fragment instanceof Name name) {
                // {@link Foo} 之類的內嵌參照，取寫出的名稱，不經過 flattener
                text.append(name.getFullyQualifiedName()).append(' ');
            }
        }
    }
}
