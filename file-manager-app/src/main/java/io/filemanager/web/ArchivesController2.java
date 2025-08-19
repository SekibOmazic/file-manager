package io.filemanager.web;

import io.filemanager.service.StreamingArchiveService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.List;

@RestController
@Slf4j
@RequestMapping("/api/archives")
public class ArchivesController2 {

    private final StreamingArchiveService streamingArchiveService;

    public ArchivesController2(StreamingArchiveService streamingArchiveService) {
        this.streamingArchiveService = streamingArchiveService;
    }

    @PostMapping("/download-zip2")
    public Mono<ResponseEntity<Flux<DataBuffer>>> downloadZipArchive(
            @RequestParam(defaultValue = "archive.zip") String zipName,
            @RequestBody List<Long> fileIds) {

        log.info("Request to create zip archive '{}' with file IDs: {}", zipName, fileIds);

        if (fileIds == null || fileIds.isEmpty()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }

        try {
            Flux<DataBuffer> zipStream = streamingArchiveService.createZipStream(fileIds);

            return Mono.just(ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "application/zip")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + zipName + "\"")
                    .body(zipStream));

        } catch (IOException e) {
            log.error("Error creating zip stream response", e);
            return Mono.just(ResponseEntity.internalServerError().build());
        }
    }
}
