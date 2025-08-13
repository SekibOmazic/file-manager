package io.filemanager.web;

import io.filemanager.service.ArchiveOrchestrationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.util.List;

@RestController
@RequestMapping("/api/archives")
public class ArchivesController {

    private final ArchiveOrchestrationService archiveOrchestrationService;

    public ArchivesController(ArchiveOrchestrationService archiveOrchestrationService) {
        this.archiveOrchestrationService = archiveOrchestrationService;
    }

    @PostMapping("/download-zip")
    public Mono<ResponseEntity<Flux<ByteBuffer>>> downloadFilesAsZip(
            @RequestBody List<Long> fileIds,
            @RequestParam(defaultValue = "archive.zip") String zipName) {

        Flux<ByteBuffer> zipStream = archiveOrchestrationService.createZipStreamFromIds(fileIds);

        return Mono.just(ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + zipName + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(zipStream));
    }
}
