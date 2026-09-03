package money.gh200.simpleweb.service;

import java.net.InetAddress;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Supplier;

import money.gh200.simpleweb.model.AppInfo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Builds the snapshot of "who am I and where am I running" that the demo page
 * and the {@code /api/info} endpoint both render.
 *
 * <p>The environment is supplied by systemd through the {@code APP_ENVIRONMENT}
 * variable, so this is the single place that decides what happens when it is
 * missing or oddly formatted. The build provenance (SHA and time) is not runtime
 * configuration at all: it is baked into the artifact by Maven resource filtering
 * and read from the classpath via {@link BuildMetadata}, so it cannot be re-stamped
 * by an environment variable.
 */
@Service
public class InfoService {

    public static final String DEFAULT_ENVIRONMENT = "local";
    public static final String DEFAULT_BUILD_SHA = "dev";
    public static final String DEFAULT_BUILD_TIME = "unknown";
    public static final String UNKNOWN_HOSTNAME = "unknown";

    private static final DateTimeFormatter SERVER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT);

    private final String applicationName;
    private final String version;
    private final String environment;
    private final String buildSha;
    private final String buildTime;
    private final Clock clock;
    private final Supplier<String> hostnameSupplier;

    @Autowired
    public InfoService(
            @Value("${app.name:SimpleWeb}") String applicationName,
            @Value("${app.version:unknown}") String version,
            @Value("${app.environment:local}") String environment) {

        this(applicationName, version, environment, BuildMetadata.fromClasspath(),
                Clock.systemDefaultZone(), InfoService::resolveSystemHostname);
    }

    InfoService(String applicationName, String version, String environment,
            BuildMetadata buildMetadata, Clock clock, Supplier<String> hostnameSupplier) {

        this(applicationName, version, environment, buildMetadata.sha(), buildMetadata.time(),
                clock, hostnameSupplier);
    }

    InfoService(String applicationName, String version, String environment, String buildSha,
            String buildTime, Clock clock, Supplier<String> hostnameSupplier) {

        this.applicationName = orDefault(applicationName, "SimpleWeb");
        this.version = orDefault(version, "unknown");
        this.environment = normalizeEnvironment(environment);
        this.buildSha = orDefault(buildSha, DEFAULT_BUILD_SHA);
        this.buildTime = orDefault(buildTime, DEFAULT_BUILD_TIME);
        this.clock = clock;
        this.hostnameSupplier = hostnameSupplier;
    }

    public AppInfo currentInfo() {
        return new AppInfo(
                applicationName,
                version,
                environment,
                buildSha,
                buildTime,
                hostname(),
                System.getProperty("java.version"),
                serverTime());
    }

    /** CSS class that drives the big colour-coded banner on the home page. */
    public String bannerClass() {
        return switch (environment) {
            case "test" -> "env-test";
            case "production" -> "env-production";
            default -> "env-local";
        };
    }

    public String environment() {
        return environment;
    }

    private String serverTime() {
        return ZonedDateTime.now(clock).format(SERVER_TIME_FORMAT);
    }

    private String hostname() {
        try {
            return orDefault(hostnameSupplier.get(), UNKNOWN_HOSTNAME);
        } catch (RuntimeException ex) {
            return UNKNOWN_HOSTNAME;
        }
    }

    /**
     * Unknown or misspelled values fall back to {@code local} so the page never
     * shows a production-red banner by accident.
     */
    static String normalizeEnvironment(String value) {
        String normalized = orDefault(value, DEFAULT_ENVIRONMENT).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "test", "production", "local" -> normalized;
            default -> DEFAULT_ENVIRONMENT;
        };
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private static String resolveSystemHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ex) {
            String fromEnv = System.getenv("COMPUTERNAME");
            if (fromEnv == null || fromEnv.isBlank()) {
                fromEnv = System.getenv("HOSTNAME");
            }
            return (fromEnv == null || fromEnv.isBlank()) ? UNKNOWN_HOSTNAME : fromEnv;
        }
    }
}
