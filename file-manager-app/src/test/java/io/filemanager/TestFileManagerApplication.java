package io.filemanager;

import org.springframework.boot.SpringApplication;

public class TestFileManagerApplication {

    public static void main(String[] args) {
        SpringApplication.from(FileManagerApplication::main)
                .with(TestcontainersConfiguration.class)
                .withAdditionalProfiles("localdev", "full-integration")
                .run(args);
    }

}
