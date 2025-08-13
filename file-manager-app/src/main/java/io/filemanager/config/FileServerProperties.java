package io.filemanager.config;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FileServerProperties {
    @Builder.Default
    private boolean secure = false;
    private String host;
    private Integer port;
    private Integer connectionTimeoutMs;
    private Integer responseTimeoutSeconds;

    public String getUri() {
        String schema = secure ? "https" : "http";
        return schema + "://" + host + ":" + port;
    }
}
