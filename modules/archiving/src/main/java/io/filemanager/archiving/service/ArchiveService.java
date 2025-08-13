package io.filemanager.archiving.service;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

@Slf4j
@Service
public class ArchiveService {

    public sealed interface ZipCreationResult permits Success, Failure {}

    public record Success(
            String fileName,
            Flux<ByteBuffer> fileContent
    ) implements ZipCreationResult {}

    public record Failure(
            String fileName,
            String storageType,
            String fileKey,
            String errorMessage,
            Instant failureTime
    ) implements ZipCreationResult {}


    @Data
    @RequiredArgsConstructor
    private static class ZipEntryInfo {
        private final String fileName;
        private final long crc;
        private final long compressedSize;
        private final long uncompressedSize;
        private final int compressionMethod;
        private long localHeaderOffset;

        public byte[] getFileNameBytes() {
            return fileName.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static final int COMPRESSION_METHOD_DEFLATED = 8;
    private static final int COMPRESSION_METHOD_STORED = 0;
    private static final short GP_FLAG_DATA_DESCRIPTOR = 1 << 3;


    /**
     * Main entry point for the new service. Consumes a stream of results
     * and produces a single, on-the-fly ZIP stream.
     */
    public Flux<ByteBuffer> createZipStream(Flux<ZipCreationResult> results) {
        final List<ZipEntryInfo> zipEntries = new ArrayList<>();
        final List<Failure> failures = new ArrayList<>();

        Flux<ByteBuffer> fileDataStreams = results.concatMap(result ->
                switch (result) {
                    // For each successful download, create the ZIP entry stream.
                    case Success s ->
                        // This is where we subscribe to the actual file content stream.
                        // We must handle errors that can happen at this exact moment.
                            createSuccessEntryStream(s, zipEntries)
                                    .onErrorResume(error -> {
                                        // If creating the entry fails (e.g., download fails),
                                        // add it to our list of failures and continue with an empty stream.
                                        log.error("Failed to process stream for file '{}': {}", s.fileName(), error.getMessage());
                                        failures.add(new Failure(
                                                s.fileName(),
                                                "N/A", // We don't have storage info here, but that's acceptable
                                                "N/A",
                                                error.getMessage(),
                                                Instant.now()
                                        ));
                                        return Flux.empty();
                                    });

                    // For each failure, add it to our list and produce no bytes for it.
                    case Failure f -> {
                        failures.add(f);
                        yield Flux.empty();
                    }
                }
        );

        // Defer ensures these parts are only created after the main file stream is complete.
        Flux<ByteBuffer> withErrorReport = fileDataStreams
                .concatWith(Flux.defer(() -> createErrorReportEntryIfNeeded(failures, zipEntries)));

        return withErrorReport.concatWith(Flux.defer(() -> createCentralDirectoryStream(zipEntries)));
    }


    /**
     * This is the refactored version of your old 'createZipEntryStream'.
     * It no longer downloads or handles errors; it just processes a successful stream.
     */
    private Flux<ByteBuffer> createSuccessEntryStream(Success success, List<ZipEntryInfo> zipEntries) {
        final CRC32 crc = new CRC32();
        final Deflater deflater = new Deflater(Deflater.DEFLATED, true);
        final AtomicLong uncompressedSize = new AtomicLong(0);

        // The input is now success.fileContent(), not a downloader call.
        Mono<List<ByteBuffer>> compressedChunksMono = success.fileContent()
                .concatMap(buffer -> {
                    uncompressedSize.addAndGet(buffer.remaining());
                    crc.update(buffer.duplicate());
                    deflater.setInput(buffer);

                    List<ByteBuffer> resultChunks = new ArrayList<>();
                    while (!deflater.needsInput()) {
                        ByteBuffer compressedChunk = ByteBuffer.allocate(8192);
                        int bytesCompressed = deflater.deflate(compressedChunk, Deflater.SYNC_FLUSH);
                        if (bytesCompressed > 0) {
                            compressedChunk.flip();
                            resultChunks.add(copyByteBuffer(compressedChunk));
                        }
                    }
                    return Flux.fromIterable(resultChunks);
                })
                .collectList();

        return compressedChunksMono.flatMapMany(compressedChunks -> {
            if (uncompressedSize.get() == 0) {
                deflater.end();
                return Flux.empty();
            }

            deflater.finish();
            ByteBuffer finalChunk = ByteBuffer.allocate(8192);
            int remainingBytes = deflater.deflate(finalChunk);
            if (remainingBytes > 0) {
                finalChunk.flip();
                compressedChunks.add(copyByteBuffer(finalChunk));
            }
            deflater.end();

            long finalUncompressedSize = uncompressedSize.get();
            long compressedSize = compressedChunks.stream().mapToLong(ByteBuffer::remaining).sum();

            ZipEntryInfo entryInfo = new ZipEntryInfo(
                    success.fileName(),
                    crc.getValue(),
                    compressedSize,
                    finalUncompressedSize,
                    COMPRESSION_METHOD_DEFLATED
            );
            zipEntries.add(entryInfo);

            return Flux.just(createLocalFileHeader(success.fileName()))
                    .concatWith(Flux.fromIterable(compressedChunks))
                    .concatWith(Mono.just(createDataDescriptor(entryInfo)));
        });
    }


    private Flux<ByteBuffer> createErrorReportEntryIfNeeded(List<Failure> failedFiles, List<ZipEntryInfo> zipEntries) {
        if (failedFiles.isEmpty()) {
            return Flux.empty();
        }
        String errorReportContent = generateErrorReportContent(failedFiles);
        byte[] errorReportBytes = errorReportContent.getBytes(StandardCharsets.UTF_8);
        String errorFileName = "FAILED_FILES_REPORT.txt";

        final CRC32 crc = new CRC32();
        crc.update(errorReportBytes);

        ZipEntryInfo errorEntryInfo = new ZipEntryInfo(
                errorFileName, crc.getValue(), errorReportBytes.length, errorReportBytes.length, COMPRESSION_METHOD_STORED
        );
        zipEntries.add(errorEntryInfo);

        Mono<ByteBuffer> localHeaderStream = Mono.fromCallable(() ->
                createLocalFileHeaderUncompressed(errorFileName, errorReportBytes.length, crc.getValue()));

        Mono<ByteBuffer> fileContentStream = Mono.fromCallable(() ->
                ByteBuffer.wrap(errorReportBytes));

        return Flux.concat(localHeaderStream, fileContentStream);
    }

    private String generateErrorReportContent(List<Failure> failedFiles) {
        StringBuilder report = new StringBuilder();
        report.append("DOWNLOAD FAILURE REPORT\n");
        report.append("======================\n\n");
        report.append("The following files could not be included in this archive due to download failures:\n\n");

        for (int i = 0; i < failedFiles.size(); i++) {
            Failure failed = failedFiles.get(i);
            report.append(String.format("%d. File: %s\n", i + 1, failed.fileName()));
            report.append(String.format("   Source: %s\n", failed.storageType()));
            report.append(String.format("   Path: %s\n", failed.fileKey()));
            report.append(String.format("   Error: %s\n", failed.errorMessage()));
            report.append(String.format("   Time: %s\n", failed.failureTime().toString()));
            report.append("\n");
        }
        report.append(String.format("Total failed files: %d\n", failedFiles.size()));
        report.append(String.format("Report generated: %s\n", Instant.now().toString()));
        return report.toString();
    }

    private Mono<ByteBuffer> createCentralDirectoryStream(List<ZipEntryInfo> zipEntries) {
        return Mono.fromCallable(() -> {
            long centralDirectoryStartOffset = calculateOffsets(zipEntries);
            int estimatedSize = zipEntries.size() * 100 + zipEntries.stream()
                    .mapToInt(entry -> entry.getFileNameBytes().length)
                    .sum();
            ByteBuffer centralDirectoryBuffer = ByteBuffer.allocate(estimatedSize);
            centralDirectoryBuffer.order(ByteOrder.LITTLE_ENDIAN);
            for (ZipEntryInfo entry : zipEntries) {
                boolean useDataDescriptor = entry.getCompressionMethod() != COMPRESSION_METHOD_STORED;
                centralDirectoryBuffer.putInt(0x02014b50); // Central directory file header signature
                centralDirectoryBuffer.putShort((short) 20); // Version made by
                centralDirectoryBuffer.putShort((short) 20); // Version needed to extract
                centralDirectoryBuffer.putShort((short) (useDataDescriptor ? GP_FLAG_DATA_DESCRIPTOR : 0)); // General purpose bit flag
                centralDirectoryBuffer.putShort((short) entry.getCompressionMethod()); // Compression method
                putDosTime(centralDirectoryBuffer, Instant.now());
                centralDirectoryBuffer.putInt((int) entry.getCrc());
                centralDirectoryBuffer.putInt((int) entry.getCompressedSize());
                centralDirectoryBuffer.putInt((int) entry.getUncompressedSize());
                centralDirectoryBuffer.putShort((short) entry.getFileNameBytes().length);
                centralDirectoryBuffer.putShort((short) 0); // Extra field length
                centralDirectoryBuffer.putShort((short) 0); // File comment length
                centralDirectoryBuffer.putShort((short) 0); // Disk number start
                centralDirectoryBuffer.putShort((short) 0); // Internal file attributes
                centralDirectoryBuffer.putInt(0); // External file attributes
                centralDirectoryBuffer.putInt((int) entry.getLocalHeaderOffset());
                centralDirectoryBuffer.put(entry.getFileNameBytes());
            }
            long centralDirectorySize = centralDirectoryBuffer.position();
            // End of Central Directory Record
            centralDirectoryBuffer.putInt(0x06054b50);
            centralDirectoryBuffer.putShort((short) 0);
            centralDirectoryBuffer.putShort((short) 0);
            centralDirectoryBuffer.putShort((short) zipEntries.size());
            centralDirectoryBuffer.putShort((short) zipEntries.size());
            centralDirectoryBuffer.putInt((int) centralDirectorySize);
            centralDirectoryBuffer.putInt((int) centralDirectoryStartOffset);
            centralDirectoryBuffer.putShort((short) 0);
            centralDirectoryBuffer.flip();
            return copyByteBuffer(centralDirectoryBuffer);
        });
    }

    private long calculateOffsets(List<ZipEntryInfo> zipEntries) {
        long currentOffset = 0;
        for (ZipEntryInfo entry : zipEntries) {
            entry.setLocalHeaderOffset(currentOffset);
            long entrySize = 30L + entry.getFileNameBytes().length + entry.getCompressedSize();
            if (entry.getCompressionMethod() != COMPRESSION_METHOD_STORED) {
                entrySize += 16;
            }
            currentOffset += entrySize;
        }
        return currentOffset;
    }

    private ByteBuffer createLocalFileHeader(String fileName) {
        byte[] fileNameBytes = fileName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(30 + fileNameBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort(GP_FLAG_DATA_DESCRIPTOR);
        buffer.putShort((short) COMPRESSION_METHOD_DEFLATED);
        putDosTime(buffer, Instant.now());
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) fileNameBytes.length);
        buffer.putShort((short) 0);
        buffer.put(fileNameBytes);
        buffer.flip();
        return buffer;
    }

    private ByteBuffer createLocalFileHeaderUncompressed(String fileName, long fileSize, long crc) {
        byte[] fileNameBytes = fileName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(30 + fileNameBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 0);
        buffer.putShort((short) COMPRESSION_METHOD_STORED);
        putDosTime(buffer, Instant.now());
        buffer.putInt((int) crc);
        buffer.putInt((int) fileSize);
        buffer.putInt((int) fileSize);
        buffer.putShort((short) fileNameBytes.length);
        buffer.putShort((short) 0);
        buffer.put(fileNameBytes);
        buffer.flip();
        return buffer;
    }

    private ByteBuffer createDataDescriptor(ZipEntryInfo info) {
        ByteBuffer buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x08074b50);
        buffer.putInt((int) info.getCrc());
        buffer.putInt((int) info.getCompressedSize());
        buffer.putInt((int) info.getUncompressedSize());
        buffer.flip();
        return buffer;
    }

    private void putDosTime(ByteBuffer buffer, Instant time) {
        ZonedDateTime zdt = ZonedDateTime.ofInstant(time, ZoneId.systemDefault());
        int timeField = (zdt.getHour() << 11) | (zdt.getMinute() << 5) | (zdt.getSecond() >> 1);
        int dateField = ((zdt.getYear() - 1980) << 9) | (zdt.getMonthValue() << 5) | zdt.getDayOfMonth();
        buffer.putShort((short) timeField);
        buffer.putShort((short) dateField);
    }

    private ByteBuffer copyByteBuffer(ByteBuffer original) {
        ByteBuffer copy = ByteBuffer.allocate(original.remaining());
        copy.put(original);
        copy.flip();
        return copy;
    }
}
