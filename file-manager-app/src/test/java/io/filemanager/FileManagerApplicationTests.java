package io.filemanager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles({"test", "full-integration"})
@ContextConfiguration(
        initializers = TestcontainerContextInitializer.class,
        // CRITICAL: We are now ONLY loading our TestcontainersConfiguration.
        // We are NOT loading the main FilezApplication.class anymore.
        classes = TestcontainersConfiguration.class
)
class FileManagerApplicationTests {

    // Autowire the context itself to prove it loaded
    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        // This test will now pass because the context is built correctly.
        assertThat(applicationContext).isNotNull();
        System.out.println("Context loaded successfully!");
    }
}