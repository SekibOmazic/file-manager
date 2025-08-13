package io.filemanager.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Configuration
public class ScannerConfig {

    @Bean
    @ConditionalOnProperty(name = "scanner.local", havingValue = "false", matchIfMissing = true)
    ScannerProperties scannerProperties(@Value("${scanner.host}") String host,
                                        @Value("${scanner.port}") Integer port) {
        return ScannerProperties.builder()
                .scannerHost(host)
                .scannerPort(port)
                .build();
    }

    @Bean
    @Qualifier("virusScannerWebClient")
    public WebClient virusScannerWebClient(ScannerProperties scannerProperties) {
        log.info("Configuring Virus Scanner WebClient to point at: {}", scannerProperties.getScannerUri());
        return WebClient.builder()
                .baseUrl(scannerProperties.getScannerUri())
                .build();
    }
}
