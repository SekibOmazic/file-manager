package io.filemanager;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
class WebClientConfig {

    // This value will be injected by Testcontainers via an environment variable
//    @Value("${main-app.service.url}")
//    private String mainAppServiceUrl;

    @Bean
    public WebClient mainAppWebClient() {
        return WebClient.builder()
                //.baseUrl(mainAppServiceUrl)
                .build();
    }
}