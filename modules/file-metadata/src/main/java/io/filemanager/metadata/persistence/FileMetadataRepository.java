package io.filemanager.metadata.persistence;

import io.filemanager.metadata.domain.FileMetadata;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FileMetadataRepository extends ReactiveCrudRepository<FileMetadata, Long> {
}