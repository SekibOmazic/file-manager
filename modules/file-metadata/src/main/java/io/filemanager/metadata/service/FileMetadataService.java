package io.filemanager.metadata.service;

import io.filemanager.metadata.domain.FileMetadata;
import io.filemanager.metadata.domain.Status;
import io.filemanager.metadata.domain.StorageType;
import io.filemanager.metadata.dto.FileMetadataDto;
import io.filemanager.metadata.persistence.FileMetadataRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;


@Service
public class FileMetadataService {

    private final FileMetadataRepository fileMetadataRepository;

    public FileMetadataService(FileMetadataRepository fileMetadataRepository) {
        this.fileMetadataRepository = fileMetadataRepository;
    }


    public Mono<FileMetadataDto> findById(Long id) {
        return fileMetadataRepository.findById(id)
                .map(this::toDto);
    }

    public Flux<FileMetadataDto> findByIds(List<Long> ids) {
        return fileMetadataRepository.findAllById(ids)
                .map(this::toDto);
    }

    public Mono<FileMetadataDto> createInitialRecord(String fileName) {
        String key = UUID.randomUUID().toString();
        String s3Key = key + "_" + fileName.trim().replace(" ", "_");

        String contentType = getContentType(fileName);

        FileMetadata fileMetadata = new FileMetadata();
        fileMetadata.setFileName(fileName);
        fileMetadata.setContentType(contentType);
        fileMetadata.setFileKey(s3Key);
        fileMetadata.setStorageType(StorageType.S3);
        fileMetadata.setSize(0L);
        fileMetadata.setStatus(Status.SCANNING);
        fileMetadata.setCreatedAt(Instant.now());

        return fileMetadataRepository.save(fileMetadata)
                .map(this::toDto);
    }

    /**
     * Finalizes the metadata record after a successful upload.
     *
     * @param id The ID of the metadata record.
     * @param size The size of the uploaded file.
     * @return A Mono containing the DTO of the updated record.
     */
    public Mono<FileMetadataDto> finalizeUpload(Long id, long size) {
        return fileMetadataRepository.findById(id)
                .flatMap(metadata -> {
                    metadata.setSize(size);
                    metadata.setStatus(Status.CLEAN);
                    // Optionally store the versionId if your schema supports it
                    return fileMetadataRepository.save(metadata);
                })
                .map(this::toDto);
    }

    public Mono<Void> deleteById(Long id) {
        return fileMetadataRepository.deleteById(id);
    }

    public Mono<Void> deleteAll() {
        return fileMetadataRepository.deleteAll();
    }


    /**
     * Saves or updates a metadata record from a DTO.
     * This is useful for creating records for pre-existing files (e.g., from an HTTP source)
     * or for making direct updates.
     *
     * @param metadataDto The DTO containing the data to save.
     * @return A Mono containing the DTO of the saved record.
     */
    public Mono<FileMetadataDto> save(FileMetadataDto metadataDto) {
        FileMetadata entity = toEntity(metadataDto);
        return fileMetadataRepository.save(entity)
                .map(this::toDto);
    }


    private FileMetadataDto toDto(FileMetadata entity) {
        return new FileMetadataDto(
                entity.getId(),
                entity.getFileName(),
                entity.getContentType(),
                entity.getFileKey(),
                entity.getStorageType(),
                entity.getSize(),
                entity.getStatus(),
                entity.getCreatedAt()
        );
    }

    private FileMetadata toEntity(FileMetadataDto dto) {
        FileMetadata entity = new FileMetadata();
        entity.setId(dto.id());
        entity.setFileName(dto.fileName());
        entity.setContentType(dto.contentType());
        entity.setFileKey(dto.fileKey());
        entity.setStorageType(dto.storageType());
        entity.setSize(dto.size());
        entity.setStatus(dto.status());
        entity.setCreatedAt(dto.createdAt()); // Preserve original creation date if it exists
        // The @LastModifiedDate annotation will handle updatedAt automatically
        return entity;
    }

    private String getContentType(String filename) {
        String contentType = "application/octet-stream";
        if (filename != null) {
            int dotIndex = filename.lastIndexOf('.');
            if (dotIndex > 0 && dotIndex < filename.length() - 1) {
                String extension = filename.substring(dotIndex + 1).toLowerCase();
                contentType = switch (extension) {
                    case "jpg", "jpeg" -> "image/jpeg";
                    case "png" -> "image/png";
                    case "pdf" -> "application/pdf";
                    default -> contentType;
                };
            }
        }
        return contentType;
    }

}