package io.filemanager.storage.s3;

import io.filemanager.common.exception.ResourceNotFoundException;
import io.filemanager.metadata.domain.StorageType;
import io.filemanager.metadata.dto.FileMetadataDto;
import io.filemanager.storage.api.FileStorage;
import io.filemanager.storage.api.UploadResult;
import io.filemanager.storage.api.exception.StorageConnectivityException;
import io.filemanager.storage.api.exception.StorageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component("s3FileStorageAdapter")
public class S3FileStorageAdapter implements FileStorage {
    private static final int MIN_PART_SIZE_BYTES = 5 * 1024 * 1024; // 5MB

    private final S3AsyncClient s3AsyncClient;
    private final String bucketName;

    public S3FileStorageAdapter(S3AsyncClient s3AsyncClient, @Value("${s3.bucket}") String bucketName) {
        this.s3AsyncClient = s3AsyncClient;
        this.bucketName = bucketName;
    }

    @Override
    public StorageType getStorageType() {
        return StorageType.S3;
    }

    @Override
    public Flux<ByteBuffer> download(FileMetadataDto metadata) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(metadata.fileKey())
                .build();

        // This is the correct, idiomatic way to get a reactive stream from the S3 Async Client.
        return Mono.fromFuture(s3AsyncClient.getObject(request, AsyncResponseTransformer.toPublisher()))
                .flatMapMany(Flux::from)
                // The error mapping is applied to the final Flux.
                .onErrorMap(throwable -> {
                    // Because we use Mono.fromFuture, we must check for CompletionException.
                    Throwable cause = (throwable instanceof CompletionException) ? throwable.getCause() : throwable;

                    if (cause instanceof NoSuchKeyException) {
                        return new ResourceNotFoundException(
                                "File not found in S3 with key: " + metadata.fileKey(), cause
                        );
                    }
                    if (cause instanceof SdkClientException) {
                        return new StorageConnectivityException(
                                "Could not connect to S3 to download key: " + metadata.fileKey(), cause
                        );
                    }
                    // Fallback for other errors.
                    return new StorageException(
                            "An unexpected S3 error occurred for key: " + metadata.fileKey(), throwable
                    );
                });
    }


    @Override
    public Mono<UploadResult> upload(String key, Flux<DataBuffer> fileContent, String contentType) {
        final AtomicLong totalSize = new AtomicLong(0);

        CreateMultipartUploadRequest createRequest = CreateMultipartUploadRequest.builder()
                .bucket(bucketName).key(key).contentType(contentType).build();

        return Mono.fromFuture(s3AsyncClient.createMultipartUpload(createRequest))
                .flatMap(createResponse -> {
                    String uploadId = createResponse.uploadId();
                    AtomicInteger partNumber = new AtomicInteger(1);

                    // Custom buffering to ensure minimum part size
                    Flux<CompletedPart> completedPartsFlux = fileContent
                            .doOnNext(dataBuffer -> totalSize.addAndGet(dataBuffer.readableByteCount()))
                            .transform(this::bufferToMinimumPartSize)
                            .filter(bufferList -> !bufferList.isEmpty())
                            .concatMap(dataBufferList -> uploadPart(uploadId, key, partNumber.getAndIncrement(), dataBufferList));

                    return completedPartsFlux.collectList()
                            .flatMap(completedParts -> completeUpload(uploadId, key, completedParts))
                            .map(response -> new UploadResult(key, response.eTag(), totalSize.get()))
                            .doOnError(ex -> {
                                log.error("Upload failed: {}", ex.getMessage());
                                abortUpload(uploadId, key);
                            });
                });
    }

    /**
     * Custom buffering that accumulates DataBuffers until we reach the minimum part size
     * or the stream completes.
     */
    private Flux<List<DataBuffer>> bufferToMinimumPartSize(Flux<DataBuffer> source) {
        return Flux.create(sink -> {
            List<DataBuffer> currentBuffer = new ArrayList<>();
            AtomicLong currentSize = new AtomicLong(0);

            source.subscribe(
                    dataBuffer -> {
                        currentBuffer.add(dataBuffer);
                        long newSize = currentSize.addAndGet(dataBuffer.readableByteCount());

                        // Emit when we reach minimum size
                        if (newSize >= MIN_PART_SIZE_BYTES) {
                            List<DataBuffer> toEmit = new ArrayList<>(currentBuffer);
                            currentBuffer.clear();
                            currentSize.set(0);
                            sink.next(toEmit);
                        }
                    },
                    error -> {
                        // On error, release any remaining buffers
                        currentBuffer.forEach(buffer -> {
                            try {
                                DataBufferUtils.release(buffer);
                            } catch (Exception ignored) {
                            }
                        });
                        sink.error(error);
                    },
                    () -> {
                        // On completion, emit any remaining buffers as the final part
                        if (!currentBuffer.isEmpty()) {
                            sink.next(new ArrayList<>(currentBuffer));
                        }
                        sink.complete();
                    }
            );
        });
    }

    private Mono<CompletedPart> uploadPart(String uploadId, String key, int partNumber, List<DataBuffer> dataBufferList) {
        int totalSize = dataBufferList.stream().mapToInt(DataBuffer::readableByteCount).sum();

        if (totalSize == 0) {
            // Release empty buffers
            dataBufferList.forEach(DataBufferUtils::release);
            return Mono.empty();
        }

        // Validate part size (except for the last part, which we can't know here)
        String currentPartSize = String.format("%.2f", totalSize / 1024.0 / 1024.0);
        if (totalSize < MIN_PART_SIZE_BYTES) {
            log.warn("Part {} is smaller than 5MB ({} bytes). This might be the last part.", partNumber, currentPartSize);
        }

        log.info("Uploading part {} with size: {} bytes ({} MB)", partNumber, totalSize, currentPartSize);

        try {
            // Combine all DataBuffers into a single ByteBuffer
            ByteBuffer combinedBuffer = ByteBuffer.allocate(totalSize);

            for (DataBuffer dataBuffer : dataBufferList) {
                // SAFE: Copy data from DataBuffer to our own ByteBuffer
                Iterator<ByteBuffer> iterator = dataBuffer.readableByteBuffers();
                while (iterator.hasNext()) {
                    ByteBuffer bb = iterator.next();
                    combinedBuffer.put(bb);
                }
            }

            combinedBuffer.flip();

            UploadPartRequest partRequest = UploadPartRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .uploadId(uploadId)
                    .partNumber(partNumber)
                    .build();

            return Mono.fromFuture(s3AsyncClient.uploadPart(partRequest, AsyncRequestBody.fromByteBuffer(combinedBuffer)))
                    .map(response -> CompletedPart.builder()
                            .partNumber(partNumber)
                            .eTag(response.eTag())
                            .build())
                    .doFinally(signalType -> {
                        // CRITICAL: Only release DataBuffers AFTER the upload is complete
                        dataBufferList.forEach(dataBuffer -> {
                            try {
                                DataBufferUtils.release(dataBuffer);
                            } catch (Exception e) {
                                // Ignore double-release errors
                                log.error("Failed to release DataBuffer (likely already released): {}", e.getMessage());
                            }
                        });
                    });

        } catch (Exception e) {
            // If anything fails, make sure to release the buffers
            dataBufferList.forEach(dataBuffer -> {
                try {
                    DataBufferUtils.release(dataBuffer);
                } catch (Exception ignored) {
                }
            });
            return Mono.error(e);
        }
    }

    private Mono<PutObjectResponse> completeUpload(String uploadId, String key, List<CompletedPart> parts) {
        if (parts.isEmpty()) {
            return Mono.error(new IllegalStateException("Cannot complete upload with no parts"));
        }

        log.info("Completing multipart upload with {} parts", parts.size());

        parts.sort(Comparator.comparingInt(CompletedPart::partNumber));
        CompletedMultipartUpload completedMultipartUpload = CompletedMultipartUpload.builder().parts(parts).build();
        CompleteMultipartUploadRequest completeRequest = CompleteMultipartUploadRequest.builder()
                .bucket(bucketName)
                .key(key)
                .uploadId(uploadId)
                .multipartUpload(completedMultipartUpload)
                .build();

        return Mono.fromFuture(s3AsyncClient.completeMultipartUpload(completeRequest))
                .map(completeResponse -> PutObjectResponse.builder()
                        .eTag(completeResponse.eTag())
                        .versionId(completeResponse.versionId())
                        .build());
    }

    private void abortUpload(String uploadId, String key) {
        try {
            AbortMultipartUploadRequest abortRequest = AbortMultipartUploadRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .uploadId(uploadId)
                    .build();
            s3AsyncClient.abortMultipartUpload(abortRequest);
        } catch (Exception e) {
            log.error("Failed to abort multipart upload: {}", e.getMessage());
        }
    }

}
