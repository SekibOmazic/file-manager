package io.filemanager.service;

import io.filemanager.AbstractIntegrationTest;
import io.filemanager.metadata.dto.FileMetadataDto;
import io.filemanager.metadata.service.FileMetadataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@SpringBootTest
class ArchiveFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ArchiveOrchestrationService archiveOrchestrationService;
    @Autowired private FileMetadataService fileMetadataService;
    @Autowired private S3AsyncClient s3AsyncClient;
    @Autowired private FileUploadService fileUploadService;

    private final String s3Content = "This is a test file for S3.";
    private final List<Long> testCreatedFileIds = new ArrayList<>();


    @BeforeEach
    void setup() {
        try {
            s3AsyncClient.createBucket(CreateBucketRequest.builder().bucket("test-bucket").build()).join();
        } catch (Exception ignored) {}
        fileMetadataService.deleteAll().block();
    }

    @Test
    void createZipStream_withOneExistingAndOneMissingFile() {
        // --- ARRANGE ---
        DataBufferFactory bufferFactory = new DefaultDataBufferFactory();
        byte[] bytes = s3Content.getBytes(StandardCharsets.UTF_8);
        Flux<DataBuffer> dataAsFlux = Flux.just(bufferFactory.wrap(bytes));

        FileMetadataDto existingS3File = Mono.defer(() -> fileMetadataService.createInitialRecord("s3-exists.txt"))
                .flatMap(metadata -> fileUploadService.uploadScannedFileToS3(String.valueOf(metadata.id()), dataAsFlux))
                .block();

        // Sanity check
        assertThat(existingS3File).isNotNull();

        // Create the metadata for the missing file (this part is simple and correct)
        FileMetadataDto missingS3File = fileMetadataService.createInitialRecord("s3-missing.txt").block();
        missingS3File = fileMetadataService.finalizeUpload(missingS3File.id(), 123).block();

        // --- ACT ---
        List<Long> fileIds = List.of(existingS3File.id(), missingS3File.id());
        Mono<byte[]> zipBytesMono = archiveOrchestrationService.createZipStreamFromIds(fileIds)
                .reduce(new ByteArrayOutputStream(), this::aggregateBytes)
                .map(ByteArrayOutputStream::toByteArray);

        // --- ASSERT ---
        StepVerifier.create(zipBytesMono)
                .assertNext(zipBytes -> {
                    Map<String, String> unzippedContent = unzip(zipBytes);
                    assertThat(unzippedContent)
                            .containsOnlyKeys("s3-exists.txt", "FAILED_FILES_REPORT.txt");
                    assertThat(unzippedContent.get("s3-exists.txt")).isEqualTo(s3Content);
                    assertThat(unzippedContent.get("FAILED_FILES_REPORT.txt"))
                            .contains("File: s3-missing.txt")
                            .doesNotContain("File: s3-exists.txt");
                })
                .verifyComplete();
    }

    // --- Helper Methods ---
    private ByteArrayOutputStream aggregateBytes(ByteArrayOutputStream baos, ByteBuffer buffer) {
        baos.write(buffer.array(), buffer.arrayOffset(), buffer.remaining());
        return baos;
    }

    private Map<String, String> unzip(byte[] zipBytes) {
        Map<String, String> unzippedContent = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            var zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                String content = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                unzippedContent.put(zipEntry.getName(), content);
                zipEntry = zis.getNextEntry();
            }
        } catch (IOException e) {
            fail("Failed to unzip the resulting byte array", e);
        }
        return unzippedContent;
    }
}