package io.filemanager.metadata.persistence.converter;

import io.filemanager.metadata.domain.Status;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;

// @WritingConverter tells Spring Data that this is used when writing to the database
@WritingConverter
public class StatusToStringConverter implements Converter<Status, String> {

    @Override
    public String convert(Status source) {
        // Simply convert the enum to its string representation
        return source.name();
    }
}