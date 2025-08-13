package io.filemanager.metadata.config;

import io.filemanager.metadata.persistence.converter.StatusToStringConverter;
import io.filemanager.metadata.persistence.converter.StorageTypeToStringConverter;
import io.filemanager.metadata.persistence.converter.StringToStatusConverter;
import io.filemanager.metadata.persistence.converter.StringToStorageTypeConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.data.r2dbc.dialect.PostgresDialect;

@Configuration
@EnableR2dbcAuditing
public class R2dbcConfig {
    /**
     * Configures custom R2DBC conversions for the application.
     * This method registers converters for converting between
     * Status enum and String, and StorageType enum and String.
     *
     * @return R2dbcCustomConversions instance with the specified converters.
     */
    @Bean
    public R2dbcCustomConversions r2dbcCustomConversions() {
        return R2dbcCustomConversions.of(
                // Specify your dialect here, e.g., PostgresDialect.INSTANCE
                PostgresDialect.INSTANCE,
                // List all your converters
                new StatusToStringConverter(),
                new StringToStatusConverter(),
                new StorageTypeToStringConverter(),
                new StringToStorageTypeConverter()
        );
    }
}