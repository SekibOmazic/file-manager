package io.filemanager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@RestController
public class ScannerSimulatorController {

    @Value("${scanner.scan-delay-ms:2000}")
    private long scanDelayMs;
    //private final String targetUrl = "/api/files/upload-scanned"; // could be passed as a header or config property

    private final WebClient webClient;

    public ScannerSimulatorController(WebClient webClient) {
        this.webClient = webClient;
    }

    @PostMapping("/scan")
    public Mono<ResponseEntity<String>> scan(
            @RequestHeader("X-File-Id") String fileId,
            @RequestHeader("X-Marker") String marker,
            @RequestHeader(value = "X-Original-Filename", required = false) String originalFilename,
            @RequestHeader(value = "X-Content-Type", required = false) String originalContentType,
            @RequestHeader(value = "X-Target-Url") String targetUrlFromHeader,
            @RequestHeader(value = "X-Test-Fail", defaultValue = "false") boolean testFail,
            ServerHttpRequest request)
    {
        log.info("Received scan request - FileId: {},  Marker: {}, Filename: {}, ContentType: {}, TestFail: {}, TargetUrl: {}",
                fileId, marker, originalFilename, originalContentType, testFail, targetUrlFromHeader);

        // return scanWithDelayAndByteCounting(fileId, marker, originalFilename, originalContentType, testFail, request);
        return scanFileWithDelay(fileId, marker, originalFilename, originalContentType, targetUrlFromHeader, testFail, request);

    }

    public Mono<ResponseEntity<String>> scanWithDelayAndByteCounting(
            String fileId,
            String marker,
            String originalFilename,
            String originalContentType,
            String targetUrl,
            boolean testFail,
            ServerHttpRequest request)
    {
        // If test failure is requested, return error immediately
        if (testFail) {
            log.warn("Simulating scan failure due to X-Test-Fail header");
            return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("INFECTED: Test virus detected"));
        }

        // Cache the body to allow multiple operations
        // This is important to avoid consuming the stream multiple times
        // and to allow counting bytes while still being able to forward the data
        Flux<DataBuffer> body = request.getBody().cache();

        AtomicLong fileSize = new AtomicLong(0);
        Flux<DataBuffer> countedBody = body
                .doOnNext(dataBuffer -> fileSize.addAndGet(dataBuffer.readableByteCount()))
                .doOnComplete(() -> log.info("File scan completed - {} bytes processed", fileSize.get()));

        // Count bytes and simulate scanning delay
        return Mono.delay(Duration.ofMillis(scanDelayMs))
                .then(forwardCleanFile(fileId, marker, originalFilename, originalContentType, targetUrl, countedBody))
                .doOnSuccess(response -> log.info("Successfully processed file {} ({} bytes)",
                        originalFilename, fileSize.get()))
                .map(response -> ResponseEntity.ok("CLEAN: File scanned and forwarded successfully"))
                .onErrorResume(error -> {
                    log.error("Error processing file {}: {}", originalFilename, error.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body("SCAN_ERROR: " + error.getMessage()));
                });
    }


    public Mono<ResponseEntity<String>> scanFileWithDelay(
            String fileId,
            String marker,
            String originalFilename,
            String originalContentType,
            String targetUrl,
            boolean testFail,
            ServerHttpRequest request)
    {
        // If test failure is requested, return error immediately
        if (testFail) {
            log.warn("Simulating scan failure due to X-Test-Fail header");
            return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("INFECTED: Test virus detected"));
        }

        Flux<DataBuffer> body = request.getBody();
        Flux<DataBuffer> debugBody = body
                .doOnNext(buffer -> log.debug("Buffer received - readable bytes: {}", buffer.readableByteCount()))
                .doOnComplete(() -> log.debug("Body stream completed"));

        return Mono.delay(Duration.ofMillis(scanDelayMs))
                .then(forwardCleanFile(fileId, marker, originalFilename, originalContentType, targetUrl, debugBody))
                .map(response -> ResponseEntity.ok("CLEAN: File scanned and forwarded successfully"))
                .onErrorResume(error -> {
                    log.error("Error processing file {}: {}", originalFilename, error.getMessage(), error);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body("SCAN_ERROR: " + error.getMessage()));
                });
    }

    private Mono<String> forwardCleanFile(String fileId, String marker, String originalFilename,
                                          String originalContentType, String targetUrl, Flux<DataBuffer> fileContent) {

        if (fileId == null || originalFilename == null || targetUrl == null) {
            return Mono.error(new IllegalArgumentException("Missing required headers: X-File-Id or X-Original-Filename or X-Target-Url"));
        }

        log.info("Forwarding clean file to: {}", targetUrl);

        return webClient.post()
                .uri(targetUrl)
                .header("X-File-Id", fileId)
                .header("X-Original-Filename", originalFilename)
                .header("X-Content-Type", originalContentType != null ? originalContentType : "application/octet-stream")
                .header("X-Marker", marker)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(fileContent, DataBuffer.class)
                .retrieve()
                .bodyToMono(String.class)
                .doOnSuccess(response -> log.info("Successfully forwarded file {} to callback", originalFilename))
                .onErrorResume(error -> {
                    log.error("Failed to forward file {} to callback: {}", originalFilename, error.getMessage());
                    return Mono.error(new RuntimeException("Failed to forward clean file: " + error.getMessage()));
                });
    }

    // Health check endpoint
    @GetMapping("/health")
    public Mono<ResponseEntity<String>> health() {
        return Mono.just(ResponseEntity.ok("AVScanner Mock is running"));
    }
}
