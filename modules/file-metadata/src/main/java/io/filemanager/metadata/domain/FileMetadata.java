package io.filemanager.metadata.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("file")
public class FileMetadata {

//    public FileMetadata(Long id, String fileName, String contentType, StorageType storageType, long size) {
//        this.id = id;
//        this.fileName = fileName;
//        this.contentType = contentType;
//        this.storageType = storageType;
//        this.size = size;
//    }

    @Id
    private Long id;

    @Column("file_name")
    private String fileName;

    @Column("content_type")
    private String contentType; // MIME type of the file (e.g., "image/png", "application/pdf")

    @Column("file_key")
    private String fileKey; // Unique identifier for the file in storage (e.g., S3 key)

    @Column("storage_type")
    private StorageType storageType; // e.g., "S3", "LOCAL"

    private long size;

    private Status status;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;
}