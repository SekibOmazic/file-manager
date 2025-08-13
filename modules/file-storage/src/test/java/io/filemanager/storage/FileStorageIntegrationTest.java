package io.filemanager.storage;

import io.filemanager.metadata.domain.StorageType;
import io.filemanager.metadata.dto.FileMetadataDto;
import io.filemanager.storage.api.FileStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;


@SpringBootTest(classes = StorageTestApplication.class)
@Testcontainers
class FileStorageIntegrationTest {

    @Container
    private static final GenericContainer<?> s3mock = new GenericContainer<>(DockerImageName.parse("adobe/s3mock:latest")).withExposedPorts(9090);

    @Container
    private static final GenericContainer<?> nginx = new GenericContainer<>(DockerImageName.parse("nginx:alpine"))
            .withExposedPorts(80)
            .withCopyFileToContainer(MountableFile.forClasspathResource("nginx/nginx.conf"), "/etc/nginx/nginx.conf")
            .withCopyFileToContainer(MountableFile.forClasspathResource("test-files/test-file.txt"), "/usr/share/nginx/html/files/test-file.txt")
            .waitingFor(Wait.forHttp("/download/health").forStatusCode(200));

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.1-alpine");

    @DynamicPropertySource
    private static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("remote-http.base-url", () -> String.format("http://%s:%d", nginx.getHost(), nginx.getMappedPort(80)));
        registry.add("s3.endpoint", () -> String.format("http://%s:%d", s3mock.getHost(), s3mock.getMappedPort(9090)));
        registry.add("s3.bucket", () -> "test-bucket");

        // ADD THE R2DBC PROPERTIES SO THE AUTO-CONFIGURATION CAN SUCCEED
        registry.add("spring.r2dbc.url", () -> String.format("r2dbc:postgresql://%s:%d/%s",
                postgres.getHost(), postgres.getMappedPort(5432), postgres.getDatabaseName()));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        // We don't need to run a schema, so we can disable initialization
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Autowired
    @Qualifier("s3FileStorageAdapter")
    private FileStorage s3Adapter;

    @Autowired
    @Qualifier("remoteHttpStorageAdapter")
    private FileStorage remoteHttpAdapter;

    @Autowired
    private S3AsyncClient s3AsyncClient;

    @BeforeAll
    static void createS3Bucket(@Autowired S3AsyncClient s3AsyncClient) {
        CreateBucketRequest request = CreateBucketRequest.builder().bucket("test-bucket").build();
        s3AsyncClient.createBucket(request).join();
    }

    @Test
    void s3Adapter_shouldUploadAndDownloadFile() {
        String key = "my-integration-test-file.txt";
        String content = "Hello S3!";
        Flux<DataBuffer> fileContent = Flux.just(
                DefaultDataBufferFactory.sharedInstance.wrap(content.getBytes(StandardCharsets.UTF_8))
        );

        StepVerifier.create(s3Adapter.upload(key, fileContent, "text/plain"))
                .expectNextMatches(result -> result.fileKey().equals(key))
                .verifyComplete();

        FileMetadataDto metadata = new FileMetadataDto(1L, "file.txt", "text/plain", key, StorageType.S3, 0, null, null);
        Flux<ByteBuffer> downloadedContent = s3Adapter.download(metadata);

        StepVerifier.create(downloadedContent.map(this::byteBufferToString))
                .expectNext(content)
                .verifyComplete();
    }

    @Test
    void remoteHttpAdapter_shouldDownloadFile() {
        FileMetadataDto metadata = new FileMetadataDto(2L, "test-file.txt", "application/txt", "1", StorageType.HTTP, 30, null, null);
        Flux<ByteBuffer> downloadedContent = remoteHttpAdapter.download(metadata);
        Mono<Long> totalSizeMono = downloadedContent.map(ByteBuffer::remaining).map(Long::valueOf).reduce(0L, Long::sum);

        StepVerifier.create(totalSizeMono)
                .expectNext(30L)
                .verifyComplete();
    }

    private String byteBufferToString(ByteBuffer buffer) {
        return StandardCharsets.UTF_8.decode(buffer).toString();
    }
}