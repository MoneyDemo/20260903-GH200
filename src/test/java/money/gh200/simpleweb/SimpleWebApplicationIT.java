package money.gh200.simpleweb;

import java.util.Map;

import money.gh200.simpleweb.service.BuildMetadata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the real application on a random port (never a fixed one, so it can run
 * on a build agent that is already using 8080) and calls it over HTTP.
 *
 * <p>The conflicting {@code app.build.sha} / {@code app.build.time} properties below are the
 * relaxed-binding equivalents of the {@code APP_BUILD_SHA} / {@code APP_BUILD_TIME} environment
 * variables. {@code /api/info} must still report the build metadata baked into the artifact
 * resource, proving the running app cannot be re-stamped from the environment.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.environment=test",
                "app.build.sha=cccccccccccccccccccccccccccccccccccccccc",
                "app.build.time=1999-12-31T23:59:59Z"
        })
class SimpleWebApplicationIT {

    private static final String CONFLICTING_SHA = "cccccccccccccccccccccccccccccccccccccccc";
    private static final String CONFLICTING_TIME = "1999-12-31T23:59:59Z";

    @LocalServerPort
    private int port;

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    @Test
    @DisplayName("/api/info 回傳 artifact 內嵌的 build 資訊，忽略衝突的執行期環境設定")
    void infoEndpointReturnsTheEmbeddedBuildMetadataNotRuntimeOverrides() {
        BuildMetadata embedded = BuildMetadata.fromClasspath();

        var response = client().get().uri("/api/info").retrieve()
                .toEntity(new ParameterizedTypeReference<Map<String, String>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, String> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("application", "SimpleWeb")
                .containsEntry("version", "1.0.0")
                .containsEntry("environment", "test")
                .containsEntry("buildSha", embedded.sha())
                .containsEntry("buildTime", embedded.time());
        assertThat(body.get("buildSha")).isNotEqualTo(CONFLICTING_SHA);
        assertThat(body.get("buildTime")).isNotEqualTo(CONFLICTING_TIME);
        assertThat(body.get("hostname")).isNotBlank();
        assertThat(body.get("javaVersion")).startsWith("21");
        assertThat(body.get("serverTime")).isNotBlank();
    }

    @Test
    @DisplayName("/ 回傳 HTML 首頁並帶有藍色 test 橫幅與內嵌 build SHA")
    void homePageIsServedWithTheTestBanner() {
        String embeddedSha = BuildMetadata.fromClasspath().sha();

        var response = client().get().uri("/").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("TEST")
                .contains("env-test")
                .contains(embeddedSha)
                .doesNotContain(CONFLICTING_SHA);
    }

    @Test
    @DisplayName("/actuator/health 回報 UP")
    void healthEndpointReportsUp() {
        var response = client().get().uri("/actuator/health").retrieve()
                .toEntity(new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }
}
