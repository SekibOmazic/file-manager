package io.filemanager.storage.s3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.util.ReflectionUtils;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.s3.S3AsyncClient;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class S3FileStorageAdapterUnitTest {

    @Mock
    private S3AsyncClient s3AsyncClient;

    // Use @InjectMocks to get an instance of the adapter with its mocks injected
    @InjectMocks
    private S3FileStorageAdapter adapter;

    private DataBuffer createDataBuffer(int size) {
        return DefaultDataBufferFactory.sharedInstance.wrap(new byte[size]);
    }

    @Test
    void bufferToMinimumPartSize_shouldGroupBuffersCorrectly() throws Exception {
        // Arrange
        Flux<DataBuffer> source = Flux.just(
                createDataBuffer(3 * 1024 * 1024),
                createDataBuffer(3 * 1024 * 1024),
                createDataBuffer(2 * 1024 * 1024)
        );

        // Act: Use reflection to make the private method accessible
        // Find the private method by its name and parameter types
        Method privateMethod = S3FileStorageAdapter.class.getDeclaredMethod(
                "bufferToMinimumPartSize",
                Flux.class
        );
        // Make it accessible for this test
        ReflectionUtils.makeAccessible(privateMethod);

        // Invoke the method on our adapter instance
        @SuppressWarnings("unchecked") // Suppress warning as we know the return type
        Flux<List<DataBuffer>> result = (Flux<List<DataBuffer>>) ReflectionUtils.invokeMethod(
                privateMethod,
                adapter,
                source
        );

        // Assert
        assertThat(result).isNotNull();

        StepVerifier.create(result)
                .assertNext(part1 -> {
                    assertThat(part1).hasSize(2);
                    long part1Size = part1.stream().mapToLong(DataBuffer::readableByteCount).sum();
                    assertThat(part1Size).isEqualTo(6 * 1024 * 1024);
                })
                .assertNext(part2 -> {
                    assertThat(part2).hasSize(1);
                    long part2Size = part2.stream().mapToLong(DataBuffer::readableByteCount).sum();
                    assertThat(part2Size).isEqualTo(2 * 1024 * 1024);
                })
                .verifyComplete();
    }
}