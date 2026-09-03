package money.gh200.simpleweb;

import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Build-provenance guard for the generated artifact resource. The compiled
 * {@code /build-metadata.properties} on the classpath must carry the concrete build SHA/time
 * that Maven resource filtering embedded at package time, and must never mention the runtime
 * {@code APP_BUILD_SHA} / {@code APP_BUILD_TIME} environment variables.
 *
 * <p>The surefire configuration exposes the Maven {@code app.build.sha} / {@code app.build.time}
 * properties as the {@code expected.build.*} system properties, so this test can assert the
 * generated resource embeds <em>exactly</em> the value Maven was given &mdash; whether that is the
 * {@code dev}/{@code unknown} default or a {@code -Dapp.build.sha} CI override.
 */
class BuildMetadataFilteringTest {

    private Properties compiledBuildMetadata() throws Exception {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/build-metadata.properties")) {
            assertThat(in).as("classpath /build-metadata.properties").isNotNull();
            props.load(in);
        }
        return props;
    }

    @Test
    @DisplayName("產生的 build-metadata.properties 內嵌具體值，且不提及 APP_BUILD_SHA/TIME 執行期環境變數")
    void filteredResourceHoldsConcreteValuesAndNoRuntimeEnvNames() throws Exception {
        Properties props = compiledBuildMetadata();

        String sha = props.getProperty("app.build.sha");
        String time = props.getProperty("app.build.time");

        assertThat(sha)
                .as("app.build.sha must be a concrete filtered value, not an unresolved token")
                .isNotBlank()
                .doesNotContain("@")
                .doesNotStartWith("${")
                .doesNotContain("APP_BUILD_SHA");
        assertThat(time)
                .as("app.build.time must be a concrete filtered value, not an unresolved token")
                .isNotBlank()
                .doesNotContain("@")
                .doesNotStartWith("${")
                .doesNotContain("APP_BUILD_TIME");
    }

    @Test
    @EnabledIfSystemProperty(named = "expected.build.sha", matches = ".+")
    @DisplayName("Maven -Dapp.build.sha/-Dapp.build.time 會被精準地烤進 build-metadata.properties")
    void filteredResourceMatchesTheMavenPropertyExactly() throws Exception {
        Properties props = compiledBuildMetadata();

        assertThat(props.getProperty("app.build.sha"))
                .as("generated app.build.sha must equal the Maven app.build.sha property exactly")
                .isEqualTo(System.getProperty("expected.build.sha"));
        assertThat(props.getProperty("app.build.time"))
                .as("generated app.build.time must equal the Maven app.build.time property exactly")
                .isEqualTo(System.getProperty("expected.build.time"));
    }
}
