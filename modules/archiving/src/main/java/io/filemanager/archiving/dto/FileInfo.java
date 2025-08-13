package io.filemanager.archiving.dto;

import reactor.core.publisher.Flux;

import java.nio.ByteBuffer;

public record FileInfo(String fileName, String storageType, String fileKey, Flux<ByteBuffer> fileContent) {
}
