package io.filemanager.service;

import io.filemanager.metadata.domain.StorageType;
import io.filemanager.metadata.service.FileMetadataService;
import io.filemanager.storage.api.DownloadableFile;
import io.filemanager.storage.api.FileStorage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class FileDownloadService {
    private final FileMetadataService metadataService;
    private final Map<StorageType, FileStorage> storageAdapters;

    // Spring automatically injects all beans of type FileStorage into a map
    public FileDownloadService(FileMetadataService metadataService, List<FileStorage> storages) {
        this.metadataService = metadataService;
        this.storageAdapters = storages.stream()
                .collect(Collectors.toMap(FileStorage::getStorageType, Function.identity()));
    }

    public Mono<DownloadableFile> downloadFile(Long id) {
        return metadataService.findById(id)
                .flatMap(metadata -> {
                    FileStorage adapter = storageAdapters.get(metadata.storageType());
                    if (adapter == null) {
                        return Mono.error(new IllegalStateException("No storage adapter found for type: " + metadata.storageType()));
                    }
                    Flux<ByteBuffer> fileStream = adapter.download(metadata);
                    return Mono.just(new DownloadableFile(metadata.fileName(), metadata.contentType(), fileStream));
                });
    }
}
