package io.filemanager.config;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ScannerProperties {
    private String scannerHost;
    private Integer scannerPort;

    public String getScannerUri() {
        return "http://" + scannerHost + ":" + scannerPort;
    }
}
