package io.filemanager;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.NginxContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

public class TestcontainerContextInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    // Define all containers as static fields. This makes them singletons for the entire test run.
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.1-alpine")
            .withNetwork(Network.SHARED)
            .withNetworkAliases("postgres");

    static final GenericContainer<?> s3mock = new GenericContainer<>(DockerImageName.parse("adobe/s3mock:latest"))
            .withExposedPorts(9090)
            .withNetwork(Network.SHARED)
            .withNetworkAliases("s3mock");

    static final NginxContainer<?> fileServerMock = new NginxContainer<>("nginx:alpine")
            .withNetwork(Network.SHARED)
            .withNetworkAliases("file-server")
            .withCopyFileToContainer(MountableFile.forClasspathResource("nginx/nginx.conf"), "/etc/nginx/nginx.conf")
            .withCopyFileToContainer(MountableFile.forClasspathResource("test-files/"), "/usr/share/nginx/html/files/")
            .withExposedPorts(80)
            .waitingFor(Wait.forHttp("/download/health").forStatusCode(200));

    static {
        // Start all containers in parallel. This block runs only once when the class is loaded.
        postgres.start();
        s3mock.start();
        fileServerMock.start();
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        // Apply the dynamic properties from the RUNNING containers to the Spring environment.
        TestPropertyValues.of(
                "spring.r2dbc.url=" + String.format("r2dbc:postgresql://%s:%d/%s", postgres.getHost(), postgres.getMappedPort(5432), postgres.getDatabaseName()),
                "spring.r2dbc.username=" + postgres.getUsername(),
                "spring.r2dbc.password=" + postgres.getPassword(),
                // --- We are providing the properties needed by TestcontainersConfiguration directly ---
                "s3.endpoint=" + s3mock.getHost(),
                "s3.port=" + s3mock.getMappedPort(9090),
                // We can add the other properties here too to be fully self-contained
                "s3.local=true",
                "scanner.local=true",
                "file-server.local=true"
        ).applyTo(applicationContext.getEnvironment());
    }
}