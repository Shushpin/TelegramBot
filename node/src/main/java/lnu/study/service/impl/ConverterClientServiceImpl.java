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

    @Override
    public ResponseEntity<byte[]> convertFile(ByteArrayResource fileResource, String originalFileName, String targetFormat, String converterApiEndpoint) {
        RestTemplate restTemplate = new RestTemplate();
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
}