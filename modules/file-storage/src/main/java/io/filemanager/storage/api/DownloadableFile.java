package io.filemanager.storage.api;

import reactor.core.publisher.Flux;

import java.nio.ByteBuffer;

public record DownloadableFile(
    String fileName,
    String contentType,
    Flux<ByteBuffer> content
) {}