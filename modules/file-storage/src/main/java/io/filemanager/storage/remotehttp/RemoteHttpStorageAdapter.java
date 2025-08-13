package io.filemanager.storage.remotehttp;

import io.filemanager.common.exception.ResourceNotFoundException;
import io.filemanager.metadata.domain.StorageType;
import io.filemanager.metadata.dto.FileMetadataDto;
import io.filemanager.storage.api.FileStorage;
import io.filemanager.storage.api.UploadResult;
import io.filemanager.storage.api.exception.StorageConnectivityException;
import io.filemanager.storage.api.exception.StorageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;

import static org.springframework.http.HttpStatus.*;


@Slf4j
@Component
public class RemoteHttpStorageAdapter implements FileStorage {
    private final WebClient webClient;

    public RemoteHttpStorageAdapter(@Qualifier("streamingWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Mono<UploadResult> upload(String key, Flux<DataBuffer> fileContent, String contentType) {
        return Mono.error(new RuntimeException("not implemented yet"));
    }

    @Override
    public Flux<ByteBuffer> download(FileMetadataDto metadata) {
        String downloadUrl = extractDownloadUrl(metadata);

        log.info("Starting download from URL: {}", downloadUrl);

        return webClient.get()
                .uri(downloadUrl)
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .map(this::convertToByteBuffer)
                .doOnSubscribe(subscription -> log.debug("Started streaming file: {}", metadata.fileName()))
                .doOnNext(buffer -> log.trace("Received chunk of {} bytes", buffer.remaining()))
                .doOnComplete(() -> log.info("Completed download for file: {}", metadata.fileName()))
                .doOnError(error -> log.error("Error downloading file {}: {}", metadata.fileName(), error.getMessage()))
                .onErrorMap(throwable -> {
                    // Case 1: The server responded with an error status code (4xx/5xx)
                    if (throwable instanceof WebClientResponseException ex) {
                        String errorMessage = "Remote server returned error for URL " + downloadUrl + ": " + ex.getStatusCode();

                        // Map specific HTTP status codes to our custom exceptions
                        return switch (ex.getStatusCode()) {
                            // 404 Not Found is a clear case for our generic not found exception.
                            case NOT_FOUND -> new ResourceNotFoundException(errorMessage, ex);

                            // Server availability issues map well to connectivity problems.
                            case SERVICE_UNAVAILABLE, BAD_GATEWAY, GATEWAY_TIMEOUT ->
                                    new StorageConnectivityException(errorMessage, ex);

                            // Any other server error is a general StorageException.
                            default -> new StorageException(errorMessage, ex);
                        };
                    }

                    // Case 2: A network error occurred before getting a response (e.g., timeout, DNS)
                    if (throwable instanceof WebClientRequestException ex) {
                        String errorMessage = "Network error while trying to reach remote file at " + downloadUrl;
                        return new StorageConnectivityException(errorMessage, ex);
                    }

                    // Case 3: A fallback for any other unexpected exception
                    return new StorageException("An unexpected error occurred while downloading from " + downloadUrl, throwable);
                });
    }

    @Override
    public StorageType getStorageType() {
        return StorageType.HTTP;
    }

    private String extractDownloadUrl(FileMetadataDto metadata) {
        return "/download/" + metadata.fileKey();
    }

    private ByteBuffer convertToByteBuffer(DataBuffer dataBuffer) {
        try {
            // Get all available bytes from the DataBuffer
            byte[] bytes = new byte[dataBuffer.readableByteCount()];
            dataBuffer.read(bytes);

            // Create and return a ByteBuffer with the data ready to read
            return ByteBuffer.wrap(bytes);
        } finally {
            // Important: Release the DataBuffer to prevent memory leaks
            DataBufferUtils.release(dataBuffer);
        }
    }

}
