package io.filemanager.metadata;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * An abstract base class for integration tests that require a PostgreSQL database.
 * It uses the Testcontainers singleton container pattern to start the PostgreSQL container
 * only once for all tests that extend this class, significantly speeding up the test suite.
 */
@Testcontainers
public abstract class AbstractIntegrationTest {

    // The 'static' keyword is the magic for the singleton pattern.
    // This container will be started once and shared across all test classes.
    @Container
    private static final PostgreSQLContainer<?> postgresContainer =
            new PostgreSQLContainer<>("postgres:15.1-alpine")
                    .withDatabaseName("testdb")
                    .withUsername("testuser")
                    .withPassword("testpass");

    /**
     * Dynamically sets the R2DBC URL property for Spring Boot to connect to the
     * started Testcontainer.
     */
    @DynamicPropertySource
    private static void registerR2dbcProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url",
                () -> String.format("r2dbc:postgresql://%s:%d/%s",
                        postgresContainer.getHost(),
                        postgresContainer.getMappedPort(5432),
                        postgresContainer.getDatabaseName()));
        registry.add("spring.r2dbc.username", postgresContainer::getUsername);
        registry.add("spring.r2dbc.password", postgresContainer::getPassword);

        // Also, ensure schema initialization is active for tests
        registry.add("spring.sql.init.mode", () -> "always");
    }
}