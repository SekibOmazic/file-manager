package io.filemanager.metadata.service;

import io.filemanager.metadata.MetadataTestApplication;
import io.filemanager.metadata.domain.FileMetadata;
import io.filemanager.metadata.domain.Status;
import io.filemanager.metadata.domain.StorageType;
import io.filemanager.metadata.dto.FileMetadataDto;
import io.filemanager.metadata.persistence.FileMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = MetadataTestApplication.class)
@Testcontainers
class FileMetadataServiceIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> postgresContainer =
            new PostgreSQLContainer<>("postgres:15.1-alpine")
                    .withDatabaseName("testdb")
                    .withUsername("testuser")
                    .withPassword("testpass");

    @DynamicPropertySource
    private static void registerR2dbcProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> String.format("r2dbc:postgresql://%s:%d/%s",
                postgresContainer.getHost(),
                postgresContainer.getMappedPort(5432),
                postgresContainer.getDatabaseName()));
        registry.add("spring.r2dbc.username", postgresContainer::getUsername);
        registry.add("spring.r2dbc.password", postgresContainer::getPassword);
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:schema.sql");
    }

    @Autowired
    private FileMetadataService fileMetadataService;

    @Autowired
    private FileMetadataRepository fileMetadataRepository;

    @Autowired
    private TransactionalOperator transactionalOperator;

    @BeforeEach
    void cleanupDatabase() {
        fileMetadataRepository.deleteAll().block();
    }

    @Test
    void createInitialRecord_shouldSaveToDatabase() {
        String fileName = "document.docx";

        Mono<FileMetadataDto> result = fileMetadataService.createInitialRecord(fileName);

        StepVerifier.create(result)
                .assertNext(dto -> {
                    assertThat(dto.id()).isNotNull();
                    assertThat(dto.fileName()).isEqualTo(fileName);
                    assertThat(dto.status()).isEqualTo(Status.SCANNING);
                    assertThat(dto.storageType()).isEqualTo(StorageType.S3);
                })
                .verifyComplete();
    }

    @Test
    void finalizeUpload_shouldUpdateRecordInDatabase() {
        // 1. Arrange: Create the initial record.
        Mono<FileMetadata> setupMono = fileMetadataRepository.save(new FileMetadata(
                null, "image.png", "image/png", "key123", StorageType.S3, 0, Status.SCANNING, null, null
        ));

        // 2. Act: Chain the service call to happen *after* the setup is complete.
        Mono<FileMetadataDto> actMono = setupMono.flatMap(savedEntity ->
                fileMetadataService.finalizeUpload(savedEntity.getId(), 2048L)
        );

        // 3. Assert: Verify the final result of the chain.
        StepVerifier.create(actMono)
                .assertNext(dto -> {
                    assertThat(dto.size()).isEqualTo(2048L);
                    assertThat(dto.status()).isEqualTo(Status.CLEAN);
                })
                .verifyComplete();
    }
}