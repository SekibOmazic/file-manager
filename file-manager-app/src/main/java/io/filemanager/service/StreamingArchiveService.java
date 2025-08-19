package io.filemanager.service;

import io.filemanager.metadata.dto.FileMetadataDto;
import io.filemanager.metadata.domain.StorageType;
import io.filemanager.metadata.service.FileMetadataService;
import io.filemanager.storage.api.FileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@Slf4j
public class StreamingArchiveService {

    private final FileMetadataService fileMetadataService;
    private final Map<StorageType, FileStorage> storageAdapters;
    private final DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();

    /**
     * Constructor that uses Spring's dependency injection to find all beans
     * implementing the FileStorage interface and organizes them into a lookup map.
     */
    public StreamingArchiveService(FileMetadataService fileMetadataService, List<FileStorage> adapters) {
        this.fileMetadataService = fileMetadataService;
        // This creates an efficient, immutable lookup map from StorageType enum to the correct adapter bean.
        this.storageAdapters = adapters.stream()
                .collect(Collectors.toUnmodifiableMap(FileStorage::getStorageType, Function.identity()));
        log.info("Initialized StreamingArchiveService with adapters for: {}", storageAdapters.keySet());
    }

    /**
     * Creates a reactive stream representing a zip archive.
     * @param fileIds A list of database IDs for the files to include.
     * @return A Flux of DataBuffers that can be streamed as the response body.
     */
    public Flux<DataBuffer> createZipStream(List<Long> fileIds) throws IOException {
        PipedInputStream pipedInputStream = new PipedInputStream();
        PipedOutputStream pipedOutputStream = new PipedOutputStream(pipedInputStream);

        // The zip creation logic is inherently blocking, so it runs on a dedicated thread.
        Mono.fromRunnable(() -> zipFiles(pipedOutputStream, fileIds))
            .subscribeOn(Schedulers.boundedElastic())
            .doOnError(e -> log.error("Zipping process failed unexpectedly.", e))
            .doFinally(signalType -> {
                try {
                    pipedOutputStream.close();
                } catch (IOException e) {
                    log.error("Failed to close PipedOutputStream", e);
                }
            })
            .subscribe(); // Fire-and-forget subscription.

        return readInputStream(pipedInputStream);
    }

    /**
     * This method contains the blocking logic to fetch metadata, download files from various sources,
     * and write them to the ZipOutputStream. It's designed for execution on a background thread.
     */
    private void zipFiles(PipedOutputStream pipedOutputStream, List<Long> fileIds) {
        final List<String> failedFiles = new ArrayList<>();

        try (ZipOutputStream zos = new ZipOutputStream(pipedOutputStream)) {
            List<FileMetadataDto> filesToProcess = fileMetadataService.findByIds(fileIds)
                    .collectList()
                    .block();

            Set<Long> foundIds = filesToProcess.stream().map(FileMetadataDto::id).collect(Collectors.toSet());
            for (Long requestedId : fileIds) {
                if (!foundIds.contains(requestedId)) {
                    failedFiles.add("File with ID " + requestedId + " (Metadata not found)");
                }
            }

            for (FileMetadataDto metadata : filesToProcess) {
                try {
                    FileStorage adapter = storageAdapters.get(metadata.storageType());
                    if (adapter == null) {
                        throw new IllegalStateException("No storage adapter configured for type: " + metadata.storageType());
                    }
                    log.info("Processing file '{}' (ID: {}) using adapter: {}", metadata.fileName(), metadata.id(), adapter.getClass().getSimpleName());

                    // Use an AtomicBoolean to track if the zip entry has been created for this file.
                    // This is necessary to control the side-effect from within the reactive stream.
                    final AtomicBoolean entryCreated = new AtomicBoolean(false);

                    adapter.download(metadata)
                            .doOnNext(byteBuffer -> {
                                try {
                                    // CRITICAL FIX: The ZipEntry is created only when the *first*
                                    // byte buffer arrives, proving the file is accessible.
                                    if (!entryCreated.get()) {
                                        zos.putNextEntry(new ZipEntry(metadata.fileName()));
                                        entryCreated.set(true); // Ensure this only happens once per file.
                                    }

                                    byte[] bytes = new byte[byteBuffer.remaining()];
                                    byteBuffer.get(bytes);
                                    zos.write(bytes);

                                } catch (IOException e) {
                                    // Propagate IOExceptions as runtime exceptions to be caught by the outer block.
                                    throw new RuntimeException("Failed to write to zip stream for file: " + metadata.fileName(), e);
                                }
                            })
                            .doOnComplete(() -> {
                                // If an entry was successfully created, it must be closed.
                                if (entryCreated.get()) {
                                    try {
                                        zos.closeEntry();
                                    } catch (IOException e) {
                                        throw new RuntimeException("Failed to close zip entry for: " + metadata.fileName(), e);
                                    }
                                }
                            })
                            .then()
                            .block(); // Subscribe and wait for this file to complete or fail.

                } catch (Exception e) {
                    // This block will now correctly catch download failures *before* an entry is created.
                    String failureReason = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    log.error("Failed to process file '{}' (ID: {}). Reason: {}", metadata.fileName(), metadata.id(), failureReason);
                    failedFiles.add(metadata.fileName() + " (ID: " + metadata.id() + ")");
                }
            }

            // If any failures occurred, add the report file to the archive.
            if (!failedFiles.isEmpty()) {
                log.warn("Adding failure report for {} files to the zip archive.", failedFiles.size());
                zos.putNextEntry(new ZipEntry("download_failure_report.txt"));
                String reportContent = "Failed to download and add the following files to the archive:\r\n"
                        + String.join("\r\n", failedFiles);
                zos.write(reportContent.getBytes());
                zos.closeEntry();
            }

        } catch (IOException e) {
            log.error("Fatal error with ZipOutputStream. The zip stream is likely corrupt.", e);
            throw new RuntimeException(e);
        }
        log.info("Finished writing all entries to the zip stream.");
    }


    /**
     * Converts a blocking InputStream into a non-blocking Flux<DataBuffer>.
     */
    private Flux<DataBuffer> readInputStream(PipedInputStream is) {
        // This method remains unchanged from the previous solution.
        return Flux.generate(
            () -> is,
            (stream, sink) -> {
                try {
                    byte[] buffer = new byte[4096];
                    int bytesRead = stream.read(buffer);
                    if (bytesRead > 0) {
                        DataBuffer dataBuffer = bufferFactory.allocateBuffer(bytesRead);
                        dataBuffer.write(buffer, 0, bytesRead);
                        sink.next(dataBuffer);
                    } else {
                        sink.complete();
                    }
                } catch (IOException e) {
                    sink.error(e);
                }
                return stream;
            },
            stream -> {
                try {
                    stream.close();
                } catch (IOException e) {
                    log.error("Error closing input stream", e);
                }
            });
    }
}