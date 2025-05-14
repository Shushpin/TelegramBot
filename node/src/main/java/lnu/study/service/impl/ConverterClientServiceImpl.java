package lnu.study.service.impl;

import lnu.study.service.ConverterClientService;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

@Log4j2
@Service
public class ConverterClientServiceImpl implements ConverterClientService {

    @Value("${service.converter.uri}")
    private String converterServiceBaseUri;

    private final RestTemplate restTemplate;

    public ConverterClientServiceImpl(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public ResponseEntity<byte[]> convertFile(ByteArrayResource fileResource, String originalFileName, String targetFormat, String converterApiEndpoint) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource); // "file" - це ім'я параметра, яке очікує ваш converter-service
        body.add("format", targetFormat); // "format" - це ім'я параметра для формату

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        String fullUri = converterServiceBaseUri + converterApiEndpoint;
        // Наприклад, converterApiEndpoint може бути "/image/convert", "/audio/convert", "/video/convert", "/document/convert"
        // або просто "/convert" для ImageConverterController

        log.info("Sending file {} to converter service. URI: {}, Target format: {}", originalFileName, fullUri, targetFormat);

        try {
            return restTemplate.exchange(
                    fullUri,
                    HttpMethod.POST,
                    requestEntity,
                    byte[].class // Очікуємо масив байтів у відповіді
            );
        } catch (HttpStatusCodeException e) {
            log.error("Error calling converter service for file {}: {} - {}. Response body: {}",
                    originalFileName, e.getStatusCode(), e.getMessage(), e.getResponseBodyAsString(), e);
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsByteArray());
        } catch (Exception e) {
            log.error("Generic error calling converter service for file {}: {}", originalFileName, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @Override
    public ResponseEntity<byte[]> convertAudioFile(ByteArrayResource fileResource, String originalFilename, String targetFormat, String converterApiEndpoint) {
        String apiEndpoint = "/api/audio/convert"; // Специфічний ендпоінт для аудіо

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        // Переконайтеся, що ByteArrayResource має правильне ім'я файлу для multipart обробки.
        // Ваш AudioConverterController використовує file.getOriginalFilename(), що надходить від MultipartFile.
        // Наш ByteArrayResource повинен мати правильно встановлене getFilename().
        body.add("file", fileResource); // fileResource.getFilename() повинен бути originalFilename
        body.add("format", targetFormat);

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
        String url = converterServiceBaseUri + converterApiEndpoint;

        log.info("Sending audio conversion request to URL: {} for file: {}, target format: {}", url, fileResource.getFilename(), targetFormat);
        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.POST, requestEntity, byte[].class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("Audio conversion failed at {}. Status: {}. Response body: {}", url, response.getStatusCode(), response.hasBody() ? new String(response.getBody()) : "No body");
            }
            return response;
        } catch (Exception e) {
            log.error("Error calling audio converter service at {}: {}", url, e.getMessage(), e);
            byte[] errorBody = ("Failed to connect to audio conversion service: " + e.getMessage()).getBytes();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody);
        }
    }
    @Override
    public ResponseEntity<byte[]> convertVideoFile(ByteArrayResource fileResource, String originalFilename, String targetFormat, String converterApiEndpoint) {
        // converterApiEndpoint для відео буде, наприклад, "/api/video/convert"
        // Цей метод може бути майже ідентичним до convertFile, передаючи правильний endpoint.
        // Або можна просто використовувати convertFile, якщо логіка формування запиту однакова.
        // Для ясності, якщо це окремий метод:

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource); // fileResource.getFilename() має бути встановлено правильно
        body.add("format", targetFormat);

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
        String fullUri = converterServiceBaseUri + converterApiEndpoint;

        log.info("Sending video file '{}' to converter service. URI: {}, Target format: {}", originalFilename, fullUri, targetFormat);

        try {
            return this.restTemplate.exchange(
                    fullUri,
                    HttpMethod.POST,
                    requestEntity,
                    byte[].class
            );
        } catch (HttpStatusCodeException e) {
            log.error("Error calling converter service for video file '{}': {} - {}. Response body: {}",
                    originalFilename, e.getStatusCode(), e.getMessage(), e.getResponseBodyAsString(), e);
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsByteArray());
        } catch (Exception e) {
            log.error("Generic error calling converter service for video file '{}': {}", originalFilename, e.getMessage(), e);
            String errorMsg = "Error communicating with video converter service: " + e.getMessage();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorMsg.getBytes());
        }
    }

}
