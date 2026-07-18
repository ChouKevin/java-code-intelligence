package com.java.semantic.syntax.adapter.jdt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.RepositorySyntax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** 逐檔隔離：單一檔案抽取失敗不得讓整個 repository 掃出零筆 */
class JdtSyntaxExtractionServiceTest {

    private static final String HOSTILE_CLASS = "Hostile";

    @Test
    void should_still_return_the_other_files_when_one_source_file_fails_to_extract(@TempDir Path tempDir)
            throws IOException {
        Path repositoryRoot = tempDir.resolve("order-service");
        Path sourceRoot = repositoryRoot.resolve("src/main/java/com/example");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Hostile.java"),
                "package com.example; public class Hostile { public String boom() { return \"\"; } }");
        Files.writeString(sourceRoot.resolve("Healthy.java"),
                "package com.example; public class Healthy { public String ok() { return \"\"; } }");
        Files.writeString(sourceRoot.resolve("AlsoHealthy.java"),
                "package com.example; public class AlsoHealthy { public String ok() { return \"\"; } }");

        RepositorySyntax syntax = new JdtSyntaxExtractionService(new ExplodingSourceSyntaxExtractor())
                .extract(repositoryRoot);

        assertThat(syntax.classes())
                .as("一個檔案拋例外時，其餘檔案的結果必須留下，否則整個 repo 靜默變成零筆")
                .extracting(ClassMetadata::fullyQualifiedName)
                .containsExactly("com.example.AlsoHealthy", "com.example.Healthy");
    }

    @Test
    void should_return_every_file_when_no_source_file_fails_to_extract(@TempDir Path tempDir) throws IOException {
        Path repositoryRoot = tempDir.resolve("order-service");
        Path sourceRoot = repositoryRoot.resolve("src/main/java/com/example");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Healthy.java"),
                "package com.example; public class Healthy { public String ok() { return \"\"; } }");

        RepositorySyntax syntax = new JdtSyntaxExtractionService(new ExplodingSourceSyntaxExtractor())
                .extract(repositoryRoot);

        assertThat(syntax.classes())
                .extracting(ClassMetadata::fullyQualifiedName)
                .containsExactly("com.example.Healthy");
    }

    /**
     * 模擬 recovered node 打到 UnsupportedTypeFormException 或 bodyDeclarations 的未檢查轉型
     * <p>
     * setStatementsRecovery(true) 會產出這段程式碼沒見過的節點形狀，失敗類別是真實的
     */
    private static final class ExplodingSourceSyntaxExtractor extends SourceSyntaxExtractor {

        @Override
        SourceSyntax extractFrom(ParsedSource parsed, MapperXmlSqlExtractor.SqlIndex sqlIndex) {
            if (parsed.source().relativePath().contains(HOSTILE_CLASS)) {
                throw new UnsupportedTypeFormException("WildcardType");
            }
            return super.extractFrom(parsed, sqlIndex);
        }
    }
}
