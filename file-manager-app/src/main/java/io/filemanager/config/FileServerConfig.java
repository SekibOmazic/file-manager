package io.filemanager.config;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class FileServerConfig {

    @Bean
    @ConditionalOnProperty(name = "file-server.local", havingValue = "false", matchIfMissing = true)
    FileServerProperties fileServerProperties(@Value("${file-server.host}") String host,
                                              @Value("${file-server.port}") Integer port,
                                              @Value("${file-server.secure:false}") Boolean secure,
                                              @Value("${file-server.connection-timeout-ms:10000}") Integer connectionTimeoutMs,
                                              @Value("${file-server.response-timeout-seconds:600}") Integer responseTimeoutSeconds) {
        return FileServerProperties.builder()
                .secure(secure)
                .host(host)
                .port(port)
                .connectionTimeoutMs(connectionTimeoutMs)
                .responseTimeoutSeconds(responseTimeoutSeconds)
                .build();
    }

    @Bean
    @Qualifier("streamingWebClient")
    public WebClient streamingWebClient(FileServerProperties fileServerProperties) {
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create()
                                .responseTimeout(Duration.ofSeconds(fileServerProperties.getResponseTimeoutSeconds()))
                                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, fileServerProperties.getConnectionTimeoutMs())
                                // Configure smaller buffer sizes for more frequent chunks
                                .option(ChannelOption.SO_RCVBUF, 8192) // 8KB receive buffer
                ))
                .codecs(configurer -> {
                    configurer.defaultCodecs()
                            // Keep this small to avoid buffering large amounts
                            .maxInMemorySize(256 * 1024); // 256KB max per chunk
                })
                .baseUrl(fileServerProperties.getUri())
                .build();
    }
}