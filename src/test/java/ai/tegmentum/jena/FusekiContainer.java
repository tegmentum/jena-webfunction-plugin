package ai.tegmentum.jena;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.io.File;
import java.time.Duration;

/**
 * Testcontainers wrapper for Apache Jena Fuseki. Boots {@code stain/jena-fuseki},
 * mounts the shaded plugin JAR into Fuseki's {@code extra} directory so
 * {@code JenaSubsystemLifecycle} SPI discovers our webfunction registrations at
 * server startup, and creates an in-memory dataset for query execution.
 *
 * <p>Meant for {@code *IT.java} tests run under maven-failsafe-plugin in the
 * {@code integration-test} phase, so the shaded JAR exists at test time.
 */
public final class FusekiContainer extends GenericContainer<FusekiContainer> {

    public static final String DEFAULT_IMAGE = "stain/jena-fuseki:latest";
    public static final int FUSEKI_PORT = 3030;

    private static final String CONTAINER_EXTRA_DIR = "/fuseki/extra";

    private String datasetName = "ds";

    public FusekiContainer() {
        this(System.getProperty("fuseki.image", DEFAULT_IMAGE));
    }

    public FusekiContainer(final String image) {
        super(image);
        addExposedPort(FUSEKI_PORT);
        // Ready when the admin ping returns 200. /$/ping is Fuseki's canonical
        // liveness endpoint.
        waitingFor(Wait.forHttp("/$/ping")
                .forPort(FUSEKI_PORT)
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(2)));
    }

    /**
     * Create an in-memory dataset at the given path on Fuseki startup.
     * Default is {@code ds}.
     */
    public FusekiContainer withDataset(final String name) {
        this.datasetName = name;
        // stain/jena-fuseki entrypoint forwards command args to fuseki-server.
        withCommand("--mem", "/" + name);
        return this;
    }

    /**
     * Mount a plugin JAR into Fuseki's extra directory. Pass the path to the
     * shaded plugin JAR built by {@code mvn package}. The Fuseki launcher adds
     * jars under {@code /fuseki/extra} to the classpath at startup, so the
     * plugin's {@code JenaSubsystemLifecycle} SPI is discovered when the server
     * runs {@code JenaSystem.init()}.
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
