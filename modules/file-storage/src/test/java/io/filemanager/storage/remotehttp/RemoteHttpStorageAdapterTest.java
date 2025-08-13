package io.filemanager.storage.remotehttp;

import io.filemanager.common.exception.ResourceNotFoundException;
import io.filemanager.metadata.dto.FileMetadataDto;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.ByteBuffer;

class RemoteHttpStorageAdapterTest {

    private static MockWebServer mockWebServer;
    private RemoteHttpStorageAdapter adapter;

    @BeforeAll
    static void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterAll
    static void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void download_whenServerReturns404_shouldThrowResourceNotFoundException() {
        // Arrange
        // Create an adapter that points to our mock server
        WebClient webClient = WebClient.builder()
                .baseUrl(mockWebServer.url("/").toString())
                .build();
        adapter = new RemoteHttpStorageAdapter(webClient);

        mockWebServer.enqueue(new MockResponse().setResponseCode(404));

        FileMetadataDto metadata = new FileMetadataDto(1L, "file.txt", "text/plain", "file.txt", null, 0, null, null);

        // Act
        Flux<ByteBuffer> result = adapter.download(metadata);

        // Assert
        StepVerifier.create(result)
                .expectError(ResourceNotFoundException.class)
                .verify();
    }
}