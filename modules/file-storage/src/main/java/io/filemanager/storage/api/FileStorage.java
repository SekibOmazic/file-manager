package io.filemanager.storage.api;

import io.filemanager.metadata.domain.StorageType;
import io.filemanager.metadata.dto.FileMetadataDto;
import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;

public interface FileStorage {
    /**
     * Uploads a file stream to the storage.
     * @param key A unique key to identify the file in the storage.
     * @param fileContent The reactive stream of the file's content.
     * @param contentType The MIME type of the file.
     * @return A Mono containing the result of the upload.
     */
    Mono<UploadResult> upload(String key, Flux<DataBuffer> fileContent, String contentType);

    /**
     * Downloads a file from storage.
     * @param metadata The DTO containing information about the file.
     * @return A reactive stream of the file's content.
     */
    Flux<ByteBuffer> download(FileMetadataDto metadata);

    /**
     * Retrieves the storage type of this file storage implementation.
     * @return The storage type as an enum.
     */
    StorageType getStorageType(); // Return the enum directly
}