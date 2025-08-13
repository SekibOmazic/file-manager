package io.filemanager.metadata.persistence.converter;

import io.filemanager.metadata.domain.StorageType;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;

@WritingConverter
public class StorageTypeToStringConverter implements Converter<StorageType, String> {

    @Override
    public String convert(StorageType source) {
        return source.name();
    }
}