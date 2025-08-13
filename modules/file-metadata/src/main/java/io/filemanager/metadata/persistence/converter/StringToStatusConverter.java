package io.filemanager.metadata.persistence.converter;

import io.filemanager.metadata.domain.Status;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

// @ReadingConverter tells Spring Data that this is used when reading from the database
@ReadingConverter
public class StringToStatusConverter implements Converter<String, Status> {

    @Override
    public Status convert(String source) {
        // Convert the string from the database back to the enum.
        // Using toUpperCase() makes the mapping case-insensitive.
        try {
            return Status.valueOf(source.toUpperCase());
        } catch (IllegalArgumentException e) {
            // Handle cases where the data in the DB is invalid
            // You could return null, a default value, or throw an exception
            return null;
        }
    }
}