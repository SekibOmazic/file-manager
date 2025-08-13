package io.filemanager.storage;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * A minimal, empty Spring Boot application class used as the entry point for
 * integration tests within this module. It provides the @SpringBootApplication
 * anchor that @SpringBootTest needs to discover the application context and
 * begin auto-configuration.
 */
@SpringBootApplication
@Import(StorageTestConfiguration.class)
public class StorageTestApplication {
}