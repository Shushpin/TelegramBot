package lnu.study.service;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;

    public interface ConverterClientService {
        // Метод для конвертації файлу
        // fileResource - це сам файл, fileName - його оригінальне ім'я (важливо для multipart)
        // targetFormat - бажаний формат ("png", "mp3", "pdf" і т.д.)
        // converterApiEndpoint - частина URL після базового URI (напр., "/image/convert", "/audio/convert")
        ResponseEntity<byte[]> convertFile(ByteArrayResource fileResource, String fileName, String targetFormat, String converterApiEndpoint);

        ResponseEntity<byte[]> convertAudioFile(ByteArrayResource fileResource, String originalFilename, String targetFormat, String converterApiEndpoint);

        ResponseEntity<byte[]> convertVideoFile(ByteArrayResource fileResource, String originalFilename, String targetFormat, String converterApiEndpoint);

    }

