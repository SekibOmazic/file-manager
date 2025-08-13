package io.filemanager.metadata.service;

import io.filemanager.metadata.domain.FileMetadata;
import io.filemanager.metadata.domain.Status;
import io.filemanager.metadata.dto.FileMetadataDto;
import io.filemanager.metadata.persistence.FileMetadataRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class) // Enables Mockito
class FileMetadataServiceTest {

    @Mock
    private FileMetadataRepository fileMetadataRepository; // Create a mock repository

    @InjectMocks
    private FileMetadataService fileMetadataService; // Inject the mock into our service

    @Test
    void createInitialRecord_shouldSetDefaultsAndSave() {
        // Given: A file name for a PDF
        String fileName = "my-document.pdf";
        FileMetadata savedEntity = new FileMetadata();
        savedEntity.setId(1L);
        savedEntity.setFileName(fileName);
        savedEntity.setFileKey("fake-key-123");
        savedEntity.setContentType("application/pdf");
        savedEntity.setStatus(Status.SCANNING);

        // When: The repository's save method is called...
        when(fileMetadataRepository.save(any(FileMetadata.class)))
                .thenReturn(Mono.just(savedEntity)); // ...it should return our saved entity.

        // Then: Call the service method
        Mono<FileMetadataDto> result = fileMetadataService.createInitialRecord(fileName);

        // And: Verify the reactive stream's output
        StepVerifier.create(result)
                .assertNext(dto -> {
                    assertThat(dto.id()).isEqualTo(1L);
                    assertThat(dto.fileName()).isEqualTo(fileName);
                    assertThat(dto.contentType()).isEqualTo("application/pdf");
                    assertThat(dto.status()).isEqualTo(Status.SCANNING);
                    assertThat(dto.size()).isZero();
                    assertThat(dto.fileKey()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void finalizeUpload_shouldUpdateStatusAndSize() {
        // Given: An existing file record and a new size
        Long fileId = 42L;
        long newSize = 1024L;
        FileMetadata existingFile = new FileMetadata();
        existingFile.setId(fileId);
        existingFile.setStatus(Status.SCANNING);
        existingFile.setSize(0L);

        // When: The repository finds the file and saves it...
        when(fileMetadataRepository.findById(fileId)).thenReturn(Mono.just(existingFile));
        // We can mock the save call to return the modified object
        when(fileMetadataRepository.save(any(FileMetadata.class))).thenAnswer(invocation ->
                Mono.just(invocation.getArgument(0, FileMetadata.class)));

        // Then: Call the service method
        Mono<FileMetadataDto> result = fileMetadataService.finalizeUpload(fileId, newSize);

        // And: Verify the DTO reflects the changes
        StepVerifier.create(result)
                .assertNext(dto -> {
                    assertThat(dto.id()).isEqualTo(fileId);
                    assertThat(dto.status()).isEqualTo(Status.CLEAN);
                    assertThat(dto.size()).isEqualTo(newSize);
                })
                .verifyComplete();
    }
}