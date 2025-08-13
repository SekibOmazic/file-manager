package io.filemanager.web;

import io.filemanager.service.FileDownloadService;
import io.filemanager.service.FileUploadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;

@Slf4j
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileUploadService fileUploadService;
    private final FileDownloadService fileDownloadService;

    public FileController(FileUploadService fileUploadService, FileDownloadService fileDownloadService) {
        this.fileUploadService = fileUploadService;
        this.fileDownloadService = fileDownloadService;
    }

    /**
     * Endpoint 1: Accepts file upload and forwards to AV scanner
     */
    @PostMapping(value = "/upload", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Mono<ResponseEntity<String>> upload(@RequestHeader("X-File-Name") String fileName,
                                               ServerHttpRequest request) {

        Flux<DataBuffer> body = request.getBody();

        return fileUploadService.processRawFileUpload(fileName, body)
                .flatMap(fileDto -> Mono.just(fileDto.id().toString()))
                .map(ResponseEntity::ok)
                .onErrorResume(error ->
                        Mono.just(ResponseEntity.status(500)
                                .body("Failed to scan file " + fileName + ": " + error.getMessage())));
    }

    /**
     * Endpoint 2: Accepts scanned file and uploads to S3
     */
    @PostMapping(value = "/upload-scanned", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Mono<ResponseEntity<String>> uploadScanned(
            @RequestHeader("X-File-Id") String fileId,
            @RequestHeader("X-Marker") String marker,
            @RequestHeader(value = "X-Original-Filename", required = false) String originalFilename,
            @RequestHeader(value = "X-Content-Type", required = false) String originalContentType,
            ServerHttpRequest request) {

        log.info("Received upload scanned request - FileId: {}, Filename: {}, ContentType: {}, Marker: {}",
                fileId, originalFilename, originalContentType, marker);

        Flux<DataBuffer> body = request.getBody();

        return fileUploadService.uploadScannedFileToS3(fileId, body)
                .flatMap(file -> Mono.just(String.format("Successfully uploaded file %s. Bytes stored %s", fileId, file.size())))
                .map(ResponseEntity::ok)
                .onErrorResume(error -> Mono.just(ResponseEntity.badRequest()
                        .body(error.getMessage())));
    }

    /**
     * Downloads a file by its database ID, setting the correct filename in the response header.
     *
     * @param id The primary key of the file in the database.
     * @return A Mono containing the ResponseEntity with the file stream, or a 404 Not Found if the ID does not exist.
     */
    @GetMapping("/download/{id}")
    public Mono<ResponseEntity<Flux<ByteBuffer>>> downloadFileById(@PathVariable Long id) {
        return fileDownloadService.downloadFile(id)
                .map(downloadableFile -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadableFile.fileName() + "\"")
                        .contentType(MediaType.parseMediaType(downloadableFile.contentType()))
                        .body(downloadableFile.content()))
                .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()));
    }

}