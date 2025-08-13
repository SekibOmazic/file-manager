package io.filemanager;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * An abstract base class for all full application integration tests.
 *
 * It uses @ContextConfiguration to take manual control of the Spring context,
 * loading the main application BUT ALSO the TestcontainersConfiguration.
 * The beans defined in TestcontainersConfiguration will override any conflicting
 * beans from the main application (like S3Properties).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles({"test", "full-integration"})
@ContextConfiguration(
        initializers = TestcontainerContextInitializer.class, // Use an initializer for containers
        classes = {FileManagerApplication.class, TestcontainersConfiguration.class} // Load both configs
)
public abstract class AbstractIntegrationTest {
}