package money.gh200.simpleweb.web;

import money.gh200.simpleweb.service.BuildMetadata;
import money.gh200.simpleweb.service.InfoService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * The home page renders the environment (from configuration) and the build metadata baked into
 * the artifact. The conflicting {@code app.build.sha} property below is the relaxed-binding
 * equivalent of {@code APP_BUILD_SHA}; the page must show the embedded SHA, never that override.
 */
@WebMvcTest(HomeController.class)
@Import(InfoService.class)
@TestPropertySource(properties = {
        "app.environment=test",
        "app.build.sha=cccccccccccccccccccccccccccccccccccccccc",
        "app.build.time=1999-12-31T23:59:59Z"
})
class HomeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("首頁回 200 並顯示環境名稱與 artifact 內嵌的 build SHA，忽略衝突的執行期設定")
    void homePageShowsTheEnvironmentAndEmbeddedBuildInformation() throws Exception {
        String embeddedSha = BuildMetadata.fromClasspath().sha();

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(content().string(containsString("TEST")))
                .andExpect(content().string(containsString("env-test")))
                .andExpect(content().string(containsString(embeddedSha)))
                .andExpect(content().string(not(containsString(
                        "cccccccccccccccccccccccccccccccccccccccc"))));
    }
}
