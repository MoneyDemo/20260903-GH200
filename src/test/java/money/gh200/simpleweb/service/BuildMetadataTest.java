package money.gh200.simpleweb.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BuildMetadata}, the production code that reads build provenance
 * straight off the classpath. These exercise the real parsing/normalisation, not a mock.
 */
class BuildMetadataTest {

    @Test
    @DisplayName("讀取被 Maven filtering 填好的資源，回傳精確的 sha/time")
    void readsFilteredValuesExactlyFromResource() {
        BuildMetadata metadata = BuildMetadata.fromResource("/build-metadata/filled.properties");

        assertThat(metadata.sha()).isEqualTo("1234567890abcdef1234567890abcdef12345678");
        assertThat(metadata.time()).isEqualTo("2026-01-02T03:04:05Z");
    }

    @Test
    @DisplayName("資源不存在時使用安全預設值，絕不改讀環境變數")
    void fallsBackToSafeDefaultsWhenResourceMissing() {
        BuildMetadata metadata = BuildMetadata.fromResource("/build-metadata/does-not-exist.properties");

        assertThat(metadata.sha()).isEqualTo(BuildMetadata.DEFAULT_SHA);
        assertThat(metadata.time()).isEqualTo(BuildMetadata.DEFAULT_TIME);
    }

    @Test
    @DisplayName("filtering 沒跑、殘留 @token@ 時退回安全預設值")
    void fallsBackToSafeDefaultsWhenTokenIsUnfiltered() {
        BuildMetadata metadata = BuildMetadata.fromResource("/build-metadata/unfiltered.properties");

        assertThat(metadata.sha()).isEqualTo(BuildMetadata.DEFAULT_SHA);
        assertThat(metadata.time()).isEqualTo(BuildMetadata.DEFAULT_TIME);
    }

    @Test
    @DisplayName("值為空白時退回安全預設值")
    void fallsBackToSafeDefaultsWhenValuesBlank() {
        BuildMetadata metadata = BuildMetadata.fromResource("/build-metadata/blank.properties");

        assertThat(metadata.sha()).isEqualTo(BuildMetadata.DEFAULT_SHA);
        assertThat(metadata.time()).isEqualTo(BuildMetadata.DEFAULT_TIME);
    }

    @Test
    @DisplayName("直接建構時去除前後空白")
    void trimsWhitespaceFromValues() {
        BuildMetadata metadata = new BuildMetadata("  abc123  ", "  2026-09-04T00:00:00Z  ");

        assertThat(metadata.sha()).isEqualTo("abc123");
        assertThat(metadata.time()).isEqualTo("2026-09-04T00:00:00Z");
    }

    @Test
    @DisplayName("real artifact 資源在測試 classpath 上，且是具體值")
    void classpathResourceIsPresentAndConcrete() {
        BuildMetadata metadata = BuildMetadata.fromClasspath();

        assertThat(metadata.sha()).isNotBlank().doesNotContain("@");
        assertThat(metadata.time()).isNotBlank().doesNotContain("@");
    }
}
