package io.filemanager.service;

import io.filemanager.archiving.service.ArchiveService;
import io.filemanager.metadata.dto.FileMetadataDto;
import io.filemanager.metadata.service.FileMetadataService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;

@Service
public class ArchiveOrchestrationService {
    private final FileMetadataService metadataService;
    private final FileDownloadService fileDownloadService;
    private final ArchiveService archiveService;

    public ArchiveOrchestrationService(
            FileMetadataService metadataService,
            FileDownloadService fileDownloadService,
            ArchiveService archiveService) {
        this.metadataService = metadataService;
        this.fileDownloadService = fileDownloadService;
        this.archiveService = archiveService;
    }

    public Flux<ByteBuffer> createZipStreamFromIds(List<Long> fileIds) {
        // Step 1: For each ID, attempt to download the file and transform the outcome
        // (success or failure) into a ZipCreationResult object.
        Flux<ArchiveService.ZipCreationResult> results = metadataService.findByIds(fileIds)
                .flatMap(this::downloadAndWrapResult);

        // Step 2: Pass the unified stream of results to the specialized ArchiveService.
        return archiveService.createZipStream(results);
    }

    private Flux<ArchiveService.ZipCreationResult> downloadAndWrapResult(FileMetadataDto metadata) {
        return fileDownloadService.downloadFile(metadata.id())
                // If download is successful, map it to our Success record...
                .map(downloadableFile -> new ArchiveService.Success(
                        downloadableFile.fileName(),
                        downloadableFile.content()
                ))
                // ...AND CAST it to the common sealed interface type.
                .map(success -> (ArchiveService.ZipCreationResult) success)
                // Now, onErrorResume expects a fallback of type Mono<ZipCreationResult>.
                // A Mono<Failure> is perfectly compatible with this.
                .onErrorResume(error -> Mono.just(new ArchiveService.Failure( // Use Mono.just for consistency
                        metadata.fileName(),
                        metadata.storageType().name(),
                        metadata.fileKey(),
                        error.getMessage(),
                        Instant.now()
                )))
                // We need to convert the final Mono into a Flux to match the method signature
                .flux();
    }
}