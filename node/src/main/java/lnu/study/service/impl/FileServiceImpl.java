package lnu.study.service.impl;

import lombok.extern.log4j.Log4j2;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import lnu.study.dao.AppDocumentDAO;
import lnu.study.dao.AppPhotoDAO;
import lnu.study.dao.BinaryContentDAO;
import lnu.study.entity.AppDocument;
import lnu.study.entity.AppPhoto;
import lnu.study.entity.BinaryContent;
import lnu.study.exceptions.UploadFileException;
import lnu.study.service.FileService;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;

@Log4j2
@Service
public class FileServiceImpl implements FileService {

    @Value("${token}")
    private String token;
    @Value("${service.file_info.uri}")
    private String fileInfoUri;
    @Value("${service.file_storage.uri}")
    private String fileStorageUri;

    private final AppDocumentDAO appDocumentDAO;
    private final AppPhotoDAO appPhotoDAO;
    private final BinaryContentDAO binaryContentDAO;

    public FileServiceImpl(AppDocumentDAO appDocumentDAO,
                           AppPhotoDAO appPhotoDAO,
                           BinaryContentDAO binaryContentDAO) {
        this.appDocumentDAO = appDocumentDAO;
        this.appPhotoDAO = appPhotoDAO;
        this.binaryContentDAO = binaryContentDAO;
    }

    @Override
    @Transactional
    public AppDocument processDoc(Message telegramMessage) {
        Document telegramDoc = telegramMessage.getDocument();
        if (telegramDoc == null) {
            log.error("Message does not contain a document: " + telegramMessage.getMessageId());
            return null;
        }
        String fileId = telegramDoc.getFileId();
        ResponseEntity<String> response = getFilePathResponseEntity(fileId);
        if (response.getStatusCode() == HttpStatus.OK) {
            BinaryContent persistentBinaryContent = getPersistentBinaryContent(response);
            AppDocument transientAppDoc = buildTransientAppDoc(telegramDoc, persistentBinaryContent);
            return appDocumentDAO.save(transientAppDoc);
        } else {
            log.error("Failed to get file path for fileId {}. Response: {}", fileId, response);
            throw new UploadFileException("Bad response from telegram service: " + response);
        }
    }

    @Override
    @Transactional
    public AppPhoto processPhoto(Message telegramMessage) {
        var photoSizes = telegramMessage.getPhoto();
        if (photoSizes == null || photoSizes.isEmpty()) {
            log.error("Message does not contain photos: " + telegramMessage.getMessageId());
            return null;
        }
        //TODO: пока что обрабатываем только одно фото в сообщении (найбільше за розміром)
        PhotoSize telegramPhoto = photoSizes.get(photoSizes.size() - 1);

        String fileId = telegramPhoto.getFileId();
        ResponseEntity<String> response = getFilePathResponseEntity(fileId);
        if (response.getStatusCode() == HttpStatus.OK) {
            BinaryContent persistentBinaryContent = getPersistentBinaryContent(response);
            AppPhoto transientAppPhoto = buildTransientAppPhoto(telegramPhoto, persistentBinaryContent);
            return appPhotoDAO.save(transientAppPhoto);
        } else {
            log.error("Failed to get file path for photo fileId {}. Response: {}", fileId, response);
            throw new UploadFileException("Bad response from telegram service: " + response);
        }
    }

    private BinaryContent getPersistentBinaryContent(ResponseEntity<String> response) {
        String filePath = getFilePathFromJsonResponse(response);
        byte[] fileInByte = downloadFile(filePath);
        BinaryContent transientBinaryContent = BinaryContent.builder()
                .fileAsArrayOfBytes(fileInByte)
                .build();
        log.debug("Saving BinaryContent, size: " + (fileInByte != null ? fileInByte.length : "null"));
        return binaryContentDAO.save(transientBinaryContent);
    }

    private String getFilePathFromJsonResponse(ResponseEntity<String> response) {
        try {
            JSONObject jsonObject = new JSONObject(response.getBody());
            if (jsonObject.has("result") && jsonObject.getJSONObject("result").has("file_path")) {
                return jsonObject.getJSONObject("result").getString("file_path");
            } else {
                log.error("Invalid JSON response structure from Telegram 'getFile' API: {}", response.getBody());
                throw new UploadFileException("Invalid JSON response structure from Telegram 'getFile' API.");
            }
        } catch (Exception e) {
            log.error("Error parsing JSON response from Telegram: {}", response.getBody(), e);
            throw new UploadFileException("Error parsing JSON response from Telegram.", e);
        }
    }

    private AppDocument buildTransientAppDoc(Document telegramDoc, BinaryContent persistentBinaryContent) {
        return AppDocument.builder()
                .telegramFileId(telegramDoc.getFileId())
                .docName(telegramDoc.getFileName())
                .binaryContent(persistentBinaryContent)
                .mimeType(telegramDoc.getMimeType())
                .fileSize(telegramDoc.getFileSize())
                .build();
    }

    private AppPhoto buildTransientAppPhoto(PhotoSize telegramPhoto, BinaryContent persistentBinaryContent) {
        Integer fileSize = telegramPhoto.getFileSize();
        return AppPhoto.builder()
                .telegramFileId(telegramPhoto.getFileId())
                .binaryContent(persistentBinaryContent)
                .fileSize(fileSize)
                .build();
    }

    private ResponseEntity<String> getFilePathResponseEntity(String fileId) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        HttpEntity<String> request = new HttpEntity<>(headers);

        try {
            log.debug("Requesting file path for fileId: {}", fileId);
            ResponseEntity<String> response = restTemplate.exchange(
                    fileInfoUri,
                    HttpMethod.GET,
                    request,
                    String.class,
                    token, fileId
            );
            log.debug("Received response for fileId {}: {}", fileId, response.getStatusCode());
            return response;
        } catch (Exception e) {
            log.error("Error requesting file path for fileId {}: ", fileId, e);
            throw new UploadFileException("Error requesting file path from Telegram: " + e.getMessage(), e);
        }
    }

    private byte[] downloadFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            log.error("File path provided for download is null or blank.");
            throw new UploadFileException("Invalid file path received from Telegram.");
        }

        String fullUri = fileStorageUri.replace("{token}", token)
                .replace("{filePath}", filePath);
        log.debug("Downloading file from URI: {}", fullUri);
        URL urlObj = null;
        try {
            urlObj = new URL(fullUri);
        } catch (MalformedURLException e) {
            log.error("Malformed URL exception for URI: {}", fullUri, e);
            throw new UploadFileException("Failed to create URL: " + fullUri, e);
        }

        //TODO: подумати над оптимізацією (особливо для великих файлів)
        try (InputStream is = urlObj.openStream()) {
            byte[] bytes = is.readAllBytes();
            log.debug("Successfully downloaded file, size: {} bytes", bytes.length);
            return bytes;
        } catch (IOException e) {
            log.error("IOException while downloading file from {}: ", urlObj.toExternalForm(), e);
            throw new UploadFileException("Error downloading file: " + urlObj.toExternalForm(), e);
        }
    }
}