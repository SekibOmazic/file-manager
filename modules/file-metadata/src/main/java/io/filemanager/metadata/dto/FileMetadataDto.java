package io.filemanager.metadata.dto;

import io.filemanager.metadata.domain.Status;
import io.filemanager.metadata.domain.StorageType;

import java.time.Instant;

public record FileMetadataDto(
        Long id,
        String fileName,
        String contentType,
        String fileKey,
        StorageType storageType,
        long size,
        Status status,
        Instant createdAt
) {}