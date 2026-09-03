package money.gh200.simpleweb.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Immutable build provenance baked into the artifact by Maven resource filtering.
 *
 * <p>The values live in {@code /build-metadata.properties}, whose {@code @app.build.sha@}
 * and {@code @app.build.time@} tokens Maven replaces at package time (CI passes
 * {@code -Dapp.build.sha} / {@code -Dapp.build.time}). They are read straight off the
 * classpath here &mdash; never through the Spring {@link org.springframework.core.env.Environment}
 * or {@code @Value} &mdash; so a runtime {@code APP_BUILD_SHA} / {@code APP_BUILD_TIME}
 * environment variable can never rewrite what {@code /api/info} reports about the running
 * artifact.
 *
 * <p>A missing or malformed resource (for example an artifact built without filtering, so the
 * raw {@code @app.build.sha@} token survives) falls back to the same safe defaults the rest of
 * the app uses. It never silently defers to an external environment value.
 */
public record BuildMetadata(String sha, String time) {

    public static final String RESOURCE_PATH = "/build-metadata.properties";
    public static final String SHA_PROPERTY = "app.build.sha";
    public static final String TIME_PROPERTY = "app.build.time";
    public static final String DEFAULT_SHA = "dev";
    public static final String DEFAULT_TIME = "unknown";

    public BuildMetadata {
        sha = orDefault(sha, DEFAULT_SHA);
        time = orDefault(time, DEFAULT_TIME);
    }

    /** Reads the provenance baked into this artifact from the classpath. */
    public static BuildMetadata fromClasspath() {
        return fromResource(RESOURCE_PATH);
    }

    static BuildMetadata fromResource(String resourcePath) {
        Properties props = new Properties();
        try (InputStream in = BuildMetadata.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return new BuildMetadata(DEFAULT_SHA, DEFAULT_TIME);
            }
            props.load(in);
        } catch (IOException ex) {
            throw new UncheckedIOException(
                    "Unable to read build metadata resource " + resourcePath, ex);
        }
        return new BuildMetadata(
                props.getProperty(SHA_PROPERTY),
                props.getProperty(TIME_PROPERTY));
    }

    private static String orDefault(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || isUnfilledToken(trimmed)) {
            return fallback;
        }
        return trimmed;
    }

    /** An unfiltered Maven token such as {@code @app.build.sha@} is treated as "missing". */
    private static boolean isUnfilledToken(String value) {
        return value.length() > 1 && value.startsWith("@") && value.endsWith("@");
    }
}
