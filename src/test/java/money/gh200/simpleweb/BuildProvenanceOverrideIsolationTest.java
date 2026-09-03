package money.gh200.simpleweb;

import money.gh200.simpleweb.model.AppInfo;
import money.gh200.simpleweb.service.BuildMetadata;
import money.gh200.simpleweb.service.InfoService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that build provenance comes from the JAR-internal classpath resource, not from the
 * runtime environment. The conflicting {@code app.build.sha} / {@code app.build.time} values
 * below are supplied as highest-precedence Spring properties (the same relaxed-binding target as
 * the {@code APP_BUILD_SHA} / {@code APP_BUILD_TIME} environment variables). The real
 * {@link InfoService} bean must still report the value baked into
 * {@code /build-metadata.properties}.
 *
 * <p>This is the regression guard for the reviewer-proven defect: previously
 * {@code InfoService} bound these values with {@code @Value("${app.build.sha}")}, so an
 * environment variable of higher precedence than {@code application.yml} rewrote what
 * {@code /api/info} reported.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "app.build.sha=cccccccccccccccccccccccccccccccccccccccc",
                "app.build.time=1999-12-31T23:59:59Z"
        })
class BuildProvenanceOverrideIsolationTest {

    private static final String CONFLICTING_SHA = "cccccccccccccccccccccccccccccccccccccccc";
    private static final String CONFLICTING_TIME = "1999-12-31T23:59:59Z";

    @Autowired
    private InfoService infoService;

    @Test
    @DisplayName("衝突的執行期 property 無法改變 InfoService.currentInfo() 的 build sha/time")
    void runtimePropertyOverridesCannotChangeReportedBuildMetadata() {
        BuildMetadata embedded = BuildMetadata.fromClasspath();
        AppInfo info = infoService.currentInfo();

        assertThat(info.buildSha())
                .as("build SHA must come from the embedded artifact resource, not a runtime property")
                .isEqualTo(embedded.sha())
                .isNotEqualTo(CONFLICTING_SHA);
        assertThat(info.buildTime())
                .as("build time must come from the embedded artifact resource, not a runtime property")
                .isEqualTo(embedded.time())
                .isNotEqualTo(CONFLICTING_TIME);
    }
}
