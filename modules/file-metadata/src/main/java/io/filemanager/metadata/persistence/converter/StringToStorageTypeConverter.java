package io.filemanager.metadata.persistence.converter;

import io.filemanager.metadata.domain.StorageType;
import org.jspecify.annotations.Nullable;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

@ReadingConverter
public class StringToStorageTypeConverter implements Converter<String, StorageType> {
    @Override
    public @Nullable StorageType convert(String source) {
        // Convert the string from the database back to the enum.
        // Using toUpperCase() makes the mapping case-insensitive.
        try {
            return StorageType.valueOf(source.toUpperCase());
        } catch (IllegalArgumentException e) {
            // Handle cases where the data in the DB is invalid
            // You could return null, a default value, or throw an exception
            return null;
        }
    }
}
