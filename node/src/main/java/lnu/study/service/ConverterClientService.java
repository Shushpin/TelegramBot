package lnu.study.service;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;

    public interface ConverterClientService {

        ResponseEntity<byte[]> convertFile(ByteArrayResource fileResource, String fileName, String targetFormat, String converterApiEndpoint);

        ResponseEntity<byte[]> convertAudioFile(ByteArrayResource fileResource, String originalFilename, String targetFormat, String converterApiEndpoint);

        ResponseEntity<byte[]> convertVideoFile(ByteArrayResource fileResource, String originalFilename, String targetFormat, String converterApiEndpoint);

    }

