package io.filemanager.storage;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

import java.net.URI;

@TestConfiguration
public class StorageTestConfiguration {

    // This bean satisfies the dependency for RemoteHttpStorageAdapter
    @Bean
    @Qualifier("streamingWebClient")
    public WebClient streamingWebClient(@Value("${remote-http.base-url}") String baseUrl) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    // This bean satisfies the dependency for S3FileStorageAdapter
    @Bean
    public S3AsyncClient s3AsyncClient(@Value("${s3.endpoint}") String s3Endpoint) {
        return S3AsyncClient.builder()
                .endpointOverride(URI.create(s3Endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(AnonymousCredentialsProvider.create())
                .forcePathStyle(true) // Important for mock environments
                .build();
    }
}