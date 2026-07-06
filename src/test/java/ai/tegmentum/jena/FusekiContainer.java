package ai.tegmentum.jena;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

import java.io.File;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * Testcontainers wrapper for Apache Jena Fuseki. Builds a Fuseki 6.1.0 image
 * from {@code src/test/docker/Dockerfile.fuseki} at test time (no matching
 * public image existed at the time of writing), mounts the shaded plugin JAR
 * into {@code /fuseki-extra/} so Jena's {@code JenaSubsystemLifecycle} SPI
 * discovers our webfunction registrations when {@code JenaSystem.init()} runs,
 * and boots an in-memory dataset at {@code /ds}.
 *
 * <p>Meant for {@code *IT.java} tests run under maven-failsafe-plugin in the
 * {@code integration-test} phase, so the shaded JAR exists at test time.
 */
public final class FusekiContainer extends GenericContainer<FusekiContainer> {

    public static final int FUSEKI_PORT = 3030;

    /** Absolute path inside the container where extra classpath jars are placed. */
    private static final String CONTAINER_EXTRA_DIR = "/fuseki-extra";

    /**
     * Path to the Dockerfile. Kept in the repo so the image build is
     * reproducible and version-controlled alongside the IT that consumes it.
     */
    private static final String DOCKERFILE = "src/test/docker/Dockerfile.fuseki";

    /**
     * Jena version to bake into the image — matches the {@code jena.version}
     * property in {@code pom.xml}. Override via {@code -Djena.image.version=...}.
     */
    private static final String JENA_VERSION =
            System.getProperty("jena.image.version", "6.1.0");

    private final String datasetName = "ds";

    public FusekiContainer() {
        super(new ImageFromDockerfile()
                .withDockerfile(Paths.get(DOCKERFILE))
                .withBuildArg("JENA_VERSION", JENA_VERSION));
        addExposedPort(FUSEKI_PORT);
        // Match the Fuseki-main startup log message — logged once the HTTP
        // listener is bound and datasets are wired. Log-based waits also
        // sidestep occasional HTTP flakiness through Colima's amd64-emulated
        // port forward.
        waitingFor(Wait.forLogMessage(".*Start Fuseki.*", 1)
                .withStartupTimeout(Duration.ofMinutes(2)));
    }

    /**
     * Mount a plugin JAR into the container's extra-classpath directory. Pass
     * the path to the shaded plugin JAR built by {@code mvn package}. Fuseki's
     * launcher {@code CMD} prepends {@code /fuseki-extra/*} to the classpath,
     * so the plugin's {@code JenaSubsystemLifecycle} SPI is picked up when the
     * server runs {@code JenaSystem.init()}.
     */
    public FusekiContainer withPluginJar(final String hostJarPath) {
        final File jar = new File(hostJarPath);
        if (!jar.exists()) {
            throw new IllegalStateException(
                    "plugin JAR not found (run `mvn package` first): " + hostJarPath);
        }
        withCopyFileToContainer(MountableFile.forHostPath(jar.getAbsolutePath()),
                CONTAINER_EXTRA_DIR + "/" + jar.getName());
        return this;
    }

    /**
     * Mount a wasm file into the container so {@code wf:call(<file://…>, …)}
     * inside SPARQL can resolve it. Returns the URL string usable inside SPARQL
     * (a {@code file://} URL pointing at the in-container location).
     */
    public String withWasm(final String hostPath, final String containerPath) {
        final File wasm = new File(hostPath);
        if (!wasm.exists()) {
            throw new IllegalStateException("wasm not found: " + hostPath);
        }
        withCopyFileToContainer(MountableFile.forHostPath(wasm.getAbsolutePath()), containerPath);
        return "file://" + containerPath;
    }

    /**
     * Public HTTP endpoint of the running server (host + mapped port), no
     * trailing slash.
     */
    public String getServerUrl() {
        return "http://" + getHost() + ":" + getMappedPort(FUSEKI_PORT);
    }

    /**
     * Full SPARQL query endpoint URL (e.g. {@code http://host:32831/ds/sparql}).
     */
    public String getSparqlEndpoint() {
        return getServerUrl() + "/" + datasetName + "/sparql";
    }
}
