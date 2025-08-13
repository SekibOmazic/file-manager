package io.filemanager.service;

import io.filemanager.metadata.dto.FileMetadataDto;
import io.filemanager.metadata.service.FileMetadataService;
import io.filemanager.storage.api.FileStorage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

@Service
public class FileUploadService {
    private final FileMetadataService metadataService;
    private final FileStorage fileStorage; // specific storage adapter for S3
    private final WebClient virusScannerWebClient;

    private final String TARGET_URL = "http://host.testcontainers.internal:8080/api/files/upload-scanned"; // could be passed as a header or config property
    private final String PROXY_URL = "/scan"; // TODO: configure as environment variable or property. BaseUrl is set in ScannerConfig!!!

    public FileUploadService(FileMetadataService metadataService,
                             @Qualifier("s3FileStorageAdapter") FileStorage fileStorage,
                             @Qualifier("virusScannerWebClient") WebClient virusScannerWebClient)
    {
        this.metadataService = metadataService;
        this.fileStorage = fileStorage;
        this.virusScannerWebClient = virusScannerWebClient;
    }

    public Mono<FileMetadataDto> processRawFileUpload(String filename, Flux<DataBuffer> content) {
        return metadataService.createInitialRecord(filename)
                .flatMap(savedFile -> forwardToScanner(savedFile, content));
    }

    /**
     * Upload the scanned file to S3 and update metadata
     */
    public Mono<FileMetadataDto> uploadScannedFileToS3(String fileId, Flux<DataBuffer> content) {
        return metadataService.findById(Long.valueOf(fileId))
                .flatMap(metadata -> {
                    String s3Key = metadata.fileKey();

                    // Let the S3Uploader handle DataBuffer lifecycle
                    // Do NOT manually release DataBuffers here
                    return fileStorage.upload(s3Key, content, metadata.contentType())
                            .map(uploadResult -> Tuples.of(metadata, uploadResult));
                })
                .flatMap(tuple -> {
                    FileMetadataDto metadataToUpdate = tuple.getT1();
                    var uploadResult = tuple.getT2();
                    return metadataService.finalizeUpload(metadataToUpdate.id(), uploadResult.size());
                });
    }

    private Mono<FileMetadataDto> forwardToScanner(FileMetadataDto file, Flux<DataBuffer> content) {
        return virusScannerWebClient.post()
                .uri(PROXY_URL)
                .header("X-File-Id", String.valueOf(file.id()))
                .header("X-Marker", "some-marker")
                .header("X-Original-Filename", file.fileName())
                .header("X-Content-Type", file.contentType())
                .header("X-Target-Url", TARGET_URL)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(content, DataBuffer.class)
                .retrieve()
                .bodyToMono(String.class)
                .then(Mono.just(file));
    }
}
