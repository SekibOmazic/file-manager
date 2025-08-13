package io.filemanager.storage.api;

public record UploadResult(
        String fileKey,
        String eTag, // Specific to S3, but can be useful
        long size // The final size of the uploaded file
) {}