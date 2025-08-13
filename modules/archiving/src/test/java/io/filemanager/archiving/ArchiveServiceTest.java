package io.filemanager.archiving;

import io.filemanager.archiving.service.ArchiveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class ArchiveServiceTest {

    private ArchiveService archiveService;

    @BeforeEach
    void setUp() {
        // Since it's a simple service with no dependencies, we can just instantiate it.
        archiveService = new ArchiveService();
    }

    // Helper method to create a Flux<ByteBuffer> from a String
    private Flux<ByteBuffer> content(String data) {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        return Flux.just(ByteBuffer.wrap(bytes));
    }

    @Test
    void createZipStream_withSuccessesAndFailures_shouldCreateCorrectArchive() {
        // ARRANGE: Define the input stream for the service
        // We have two successful files and one that will fail during streaming.
        Flux<ArchiveService.ZipCreationResult> inputStream = Flux.just(
            // File 1: Successful download
            new ArchiveService.Success("first.txt", content("Hello World 1")),

            // File 2: Pre-failed download (e.g., metadata lookup failed)
            new ArchiveService.Failure("second.txt", "HTTP", "key2", "404 Not Found", Instant.now()),

            // File 3: Download stream that emits an error
            new ArchiveService.Success("third.txt", Flux.error(new IOException("S3 Connection timed out")))
        );

        // ACT: Call the service and collect the resulting byte stream into a single byte array
        Mono<byte[]> zipBytesMono = archiveService.createZipStream(inputStream)
                .reduce(new ByteArrayOutputStream(), (baos, buffer) -> {
                    baos.write(buffer.array(), buffer.arrayOffset(), buffer.remaining());
                    return baos;
                })
                .map(ByteArrayOutputStream::toByteArray);


        // ASSERT: Use StepVerifier to get the final byte array and then inspect it
        StepVerifier.create(zipBytesMono)
                .assertNext(zipBytes -> {
                    // This block runs after the reactive stream is complete.
                    // Now we unzip the byte array and verify its contents.
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

                    // 1. Verify the correct files are present/absent
                    assertThat(unzippedContent)
                            .containsOnlyKeys("first.txt", "FAILED_FILES_REPORT.txt")
                            .doesNotContainKey("second.txt")
                            .doesNotContainKey("third.txt");

                    // 2. Verify the content of the successful file
                    assertThat(unzippedContent.get("first.txt")).isEqualTo("Hello World 1");

                    // 3. Verify the content of the failure report
                    String reportContent = unzippedContent.get("FAILED_FILES_REPORT.txt");
                    assertThat(reportContent).contains("DOWNLOAD FAILURE REPORT");
                    assertThat(reportContent).contains("File: second.txt"); // The pre-failed file
                    assertThat(reportContent).contains("Error: 404 Not Found");
                    assertThat(reportContent).contains("File: third.txt");  // The file that failed during streaming
                    assertThat(reportContent).contains("Error: S3 Connection timed out");
                    assertThat(reportContent).contains("Total failed files: 2");

                })
                .verifyComplete();
    }
}