package lnu.study.service.impl;

import lnu.study.dao.AppAudioDAO;
import lnu.study.entity.*;
import lnu.study.service.enums.LinkType;
import lnu.study.utils.CryptoTool;
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
import lnu.study.exceptions.UploadFileException;
import lnu.study.service.FileService;
import lnu.study.dto.ArchiveFileDetailDTO; // З common-utils
import lnu.study.dao.AppVideoDAO;
import lnu.study.entity.AppVideo;


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Log4j2
@Service
public class FileServiceImpl implements FileService {

    @Value("${token}")
    private String token;
    @Value("${service.file_info.uri}")
    private String fileInfoUri;
    @Value("${service.file_storage.uri}")
    private String fileStorageUri;
    @Value("${link.address}")
    private String linkAddress;

    private static final long MAX_VIDEO_SIZE_FOR_LINK_BYTES = 50 * 1024 * 1024;

    private final AppDocumentDAO appDocumentDAO;
    private final AppPhotoDAO appPhotoDAO;
    private final BinaryContentDAO binaryContentDAO;
    private final AppVideoDAO appVideoDAO;
    private final CryptoTool cryptoTool;
    private final AppAudioDAO appAudioDAO;


    public FileServiceImpl(AppDocumentDAO appDocumentDAO,
                           AppPhotoDAO appPhotoDAO,
                           AppAudioDAO appAudioDAO,
                           AppVideoDAO appVideoDAO,
                           BinaryContentDAO binaryContentDAO, CryptoTool cryptoTool) {
        this.appDocumentDAO = appDocumentDAO;
        this.appPhotoDAO = appPhotoDAO;
        this.appAudioDAO = appAudioDAO;
        this.appVideoDAO = appVideoDAO;
        this.binaryContentDAO = binaryContentDAO;
        this.cryptoTool = cryptoTool;
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

    @Override
    @Transactional
    public AppAudio processAudio(Message telegramMessage) {
        org.telegram.telegrambots.meta.api.objects.Audio telegramAudio = telegramMessage.getAudio();
        org.telegram.telegrambots.meta.api.objects.Voice telegramVoice = telegramMessage.getVoice();

        String fileIdInput;
        String mimeTypeInput;
        Long fileSizeInput;
        Integer durationInput;
        String fileNameInput;

        if (telegramAudio != null) {
            fileIdInput = telegramAudio.getFileId();
            mimeTypeInput = telegramAudio.getMimeType();
            fileSizeInput = telegramAudio.getFileSize();
            durationInput = telegramAudio.getDuration();
            fileNameInput = telegramAudio.getFileName();
            // Якщо ім'я файлу відсутнє, генеруємо його
            if (fileNameInput == null || fileNameInput.isBlank()) {
                String extension = mimeTypeInput != null && mimeTypeInput.contains("mpeg") ? ".mp3" : ".oga"; // Приклад
                fileNameInput = "audio_" + fileIdInput + extension;
            }
        } else if (telegramVoice != null) {
            fileIdInput = telegramVoice.getFileId();
            mimeTypeInput = telegramVoice.getMimeType();
            fileSizeInput = telegramVoice.getFileSize();
            durationInput = telegramVoice.getDuration();
            fileNameInput = "voice_" + fileIdInput + ".ogg"; // Для голосових генеруємо ім'я
        } else {
            log.warn("Повідомлення не містить аудіо або голосового повідомлення: " + telegramMessage.getMessageId());
            return null;
        }

        // Логіка завантаження файлу та збереження BinaryContent (схожа на processDoc/processPhoto)
        ResponseEntity<String> response = getFilePathResponseEntity(fileIdInput);
        if (response.getStatusCode() == HttpStatus.OK) {
            BinaryContent persistentBinaryContent = getPersistentBinaryContent(response);
            AppAudio transientAppAudio = AppAudio.builder()
                    .telegramFileId(fileIdInput)
                    .binaryContent(persistentBinaryContent)
                    .mimeType(mimeTypeInput)
                    .fileSize(fileSizeInput)
                    .duration(durationInput)
                    .fileName(fileNameInput)
                    .build();
            return appAudioDAO.save(transientAppAudio);
        } else {
            log.error("Не вдалося отримати шлях до файлу для аудіо/голосового fileId {}. Відповідь: {}", fileIdInput, response);
            throw new UploadFileException("Погана відповідь від сервісу Telegram: " + response);
        }
    }

    @Override
    @Transactional
    public AppVideo processVideo(Message telegramMessage) {
        org.telegram.telegrambots.meta.api.objects.Video telegramVideo = telegramMessage.getVideo();

        if (telegramVideo == null) {
            log.warn("Повідомлення не містить відео: {}", telegramMessage.getMessageId());
            return null;
        }

        // Перевірка розміру файлу
        if (telegramVideo.getFileSize() > MAX_VIDEO_SIZE_FOR_LINK_BYTES) {
            log.info("Відеофайл (file_id: {}) занадто великий ({} байт) для генерації посилання. Ліміт: {} байт.",
                    telegramVideo.getFileId(), telegramVideo.getFileSize(), MAX_VIDEO_SIZE_FOR_LINK_BYTES);
            // Повертаємо null, щоб MainServiceImpl міг повідомити користувача
            // Або можна кидати кастомний виняток, наприклад, FileSizeLimitExceededException
            return null;
        }

        String fileIdInput = telegramVideo.getFileId();
        String mimeTypeInput = telegramVideo.getMimeType();
        Long fileSizeInput = telegramVideo.getFileSize();
        Integer durationInput = telegramVideo.getDuration();
        Integer widthInput = telegramVideo.getWidth();
        Integer heightInput = telegramVideo.getHeight();
        String fileNameInput = telegramVideo.getFileName();

        if (fileNameInput == null || fileNameInput.isBlank()) {
            String extension = ".mp4";
            if (mimeTypeInput != null) {
                if (mimeTypeInput.contains("mp4")) extension = ".mp4";
                else if (mimeTypeInput.contains("quicktime")) extension = ".mov";
                else if (mimeTypeInput.contains("x-matroska")) extension = ".mkv";
            }
            fileNameInput = "video_" + fileIdInput + extension;
        }

        ResponseEntity<String> response = getFilePathResponseEntity(fileIdInput);
        if (response.getStatusCode() == HttpStatus.OK) {
            BinaryContent persistentBinaryContent = getPersistentBinaryContent(response);
            AppVideo transientAppVideo = AppVideo.builder()
                    .telegramFileId(fileIdInput)
                    .binaryContent(persistentBinaryContent)
                    .mimeType(mimeTypeInput)
                    .fileSize(fileSizeInput)
                    .duration(durationInput)
                    .width(widthInput)
                    .height(heightInput)
                    .fileName(fileNameInput)
                    .build();
            return appVideoDAO.save(transientAppVideo);
        } else {
            log.error("Не вдалося отримати шлях до файлу для відео fileId {}. Відповідь: {}", fileIdInput, response);
            throw new UploadFileException("Погана відповідь від сервісу Telegram: " + response);
        }
    }

    @Override
    public String generateFileName(Long docId, LinkType linkType) {
        return "";
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

    private ResponseEntity<String> getFilePathResponseEntity(String fileId) { // fileId - це правильний ID фотографії
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        HttpEntity<String> request = new HttpEntity<>(headers);

        log.error("!!!!!!!!!! [NODE FileServiceImpl] ENTERING getFilePathResponseEntity with fileId: [{}] !!!!!!!!!!", fileId);
        log.info("FileServiceImpl (node) - getFilePathResponseEntity - Attempting to get file path.");
        log.info("FileServiceImpl (node) - getFilePathResponseEntity - Injected token from @Value: [{}]", this.token);
        log.info("FileServiceImpl (node) - getFilePathResponseEntity - Received fileId parameter: [{}]", fileId);
        log.info("FileServiceImpl (node) - getFilePathResponseEntity - Using fileInfoUri from @Value (as template for RestTemplate): [{}]", this.fileInfoUri);


        String effectiveUrl = "URL_FORMATION_ERROR_OR_NULL_PARAMS";
        if (this.fileInfoUri != null && this.token != null && fileId != null) {

            log.info("FileServiceImpl (node) - getFilePathResponseEntity - URI template for RestTemplate: [{}]", this.fileInfoUri);
            log.info("FileServiceImpl (node) - getFilePathResponseEntity - Value for {{fileId}} (to be substituted by RestTemplate): [{}]", fileId);

            effectiveUrl = this.fileInfoUri.replace("{fileId}", fileId);
            log.info("FileServiceImpl (node) - getFilePathResponseEntity - Expected final URL after RestTemplate substitution: [{}]", effectiveUrl);
        } else {
            log.warn("FileServiceImpl (node) - getFilePathResponseEntity - One or more params for URL formation are null. Token: [{}], FileId: [{}], UriTemplate: [{}]", this.token, fileId, this.fileInfoUri);
        }

        try {
            log.debug("Requesting file path for fileId (original debug): {}", fileId);


            ResponseEntity<String> response = restTemplate.exchange(
                    this.fileInfoUri,
                    HttpMethod.GET,
                    request,
                    String.class,
                    fileId
            );
            log.debug("Received response for fileId {}: {}", fileId, response.getStatusCode());
            return response;
        } catch (Exception e) {
            log.error("Error during RestTemplate exchange for fileId {}: {}", fileId, e.getMessage());
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
    @Override
    public String generateLink(Long fileId, LinkType linkType) {
        var hash = cryptoTool.hashOf(fileId);
        return linkAddress + "/" +linkType+ "?id=" + hash;
    }
    @Override
    public byte[] downloadFileAsByteArray(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            log.error("File ID is null or blank for downloadFileAsByteArray.");
            throw new UploadFileException("File ID is required to download the file.");
        }
        log.info("Attempting to download file directly from Telegram for fileId: {}", fileId);
        ResponseEntity<String> response = getFilePathResponseEntity(fileId); // Використовуємо існуючий метод
        if (response.getStatusCode() == HttpStatus.OK) {
            String filePath = getFilePathFromJsonResponse(response); // Використовуємо існуючий метод

            return downloadFile(filePath);
        } else {
            log.error("Failed to get file path for fileId {} during direct download. Response: {}", fileId, response);
            throw new UploadFileException("Bad response from telegram service when getting file path: " + response);
        }
    }

    @Override
    public byte[] createZipArchiveFromTelegramFiles(List<ArchiveFileDetailDTO> filesToArchiveDetails) throws IOException {
        if (filesToArchiveDetails == null || filesToArchiveDetails.isEmpty()) {
            log.warn("Список файлів для архівування порожній або null.");
            return new byte[0]; // Повертаємо порожній масив байтів
        }

        log.info("Розпочинаю створення ZIP-архіву з {} файлів.", filesToArchiveDetails.size());

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {

            for (ArchiveFileDetailDTO fileDetail : filesToArchiveDetails) {
                String telegramFileId = fileDetail.getTelegramFileId();
                String originalFileNameInArchive = fileDetail.getOriginalFileName(); // Ім'я файлу в архіві

                log.debug("Обробка файлу для архіву: originalName='{}', telegramFileId='{}'",
                        originalFileNameInArchive, telegramFileId);

                try {

                    ResponseEntity<String> filePathResponse = getFilePathResponseEntity(telegramFileId);

                    if (filePathResponse.getStatusCode() == HttpStatus.OK) {
                        String telegramFilePath = getFilePathFromJsonResponse(filePathResponse);


                        byte[] fileBytes = downloadFile(telegramFilePath);

                        if (fileBytes != null && fileBytes.length > 0) {
                            ZipEntry zipEntry = new ZipEntry(originalFileNameInArchive);
                            zos.putNextEntry(zipEntry);
                            zos.write(fileBytes);
                            zos.closeEntry();
                            log.info("Файл '{}' успішно додано до архіву.", originalFileNameInArchive);
                        } else {
                            log.warn("Файл '{}' (file_id: {}) порожній або не вдалося завантажити байти, пропускається.",
                                    originalFileNameInArchive, telegramFileId);
                        }
                    } else {
                        log.error("Не вдалося отримати шлях для файлу '{}' (file_id: {}). Статус: {}. Файл пропускається.",
                                originalFileNameInArchive, telegramFileId, filePathResponse.getStatusCode());
                    }
                } catch (UploadFileException e) {
                    log.error("Помилка UploadFileException при обробці файлу '{}' (file_id: {}) для архіву: {}. Файл пропускається.",
                            originalFileNameInArchive, telegramFileId, e.getMessage());
                } catch (Exception e) { // Інші можливі винятки (напр. IOException при записі в ZIP)
                    log.error("Загальна помилка при обробці файлу '{}' (file_id: {}) для архіву: {}. Файл пропускається.",
                            originalFileNameInArchive, telegramFileId, e.getMessage(), e);

                }
            }
            zos.finish(); // Завершуємо запис даних ZIP (важливо перед baos.toByteArray())
            log.info("ZIP-архів успішно створено в пам'яті.");
            return baos.toByteArray();

        } catch (IOException e) {
            log.error("Критична помилка вводу-виводу при створенні ZIP-архіву: {}", e.getMessage(), e);
            throw e; // Прокидаємо IOException, як зазначено в сигнатурі методу
        }
    }

    @Override
    @Transactional // Важливо для операцій збереження в БД
    public AppDocument saveGeneratedArchive(AppUser appUser, byte[] archiveBytes, String archiveFileName) {
        if (appUser == null || archiveBytes == null || archiveBytes.length == 0 || archiveFileName == null || archiveFileName.isBlank()) {
            log.error("Некоректні параметри для збереження архіву: appUser={}, archiveBytes_length={}, archiveFileName='{}'",
                    appUser, (archiveBytes != null ? archiveBytes.length : "null"), archiveFileName);
            // Можна кинути виняток або повернути null, залежно від бажаної обробки помилок
            throw new IllegalArgumentException("Некоректні параметри для збереження архіву.");
        }

        try {
            // 1. Створюємо та зберігаємо BinaryContent
            BinaryContent binaryContent = BinaryContent.builder()
                    .fileAsArrayOfBytes(archiveBytes)
                    .build();
            BinaryContent savedBinaryContent = binaryContentDAO.save(binaryContent);
            log.info("BinaryContent для архіву '{}' збережено, id={}", archiveFileName, savedBinaryContent.getId());

            // 2. Створюємо та зберігаємо AppDocument
            AppDocument archiveDocument = AppDocument.builder()
                    .telegramFileId("archive_" + appUser.getTelegramUserId() + "_" + System.currentTimeMillis()) // Генеруємо унікальний ID
                    .docName(archiveFileName)
                    .binaryContent(savedBinaryContent)
                    .mimeType("application/zip") // Стандартний MIME-тип для ZIP-архівів
                    .fileSize((long) archiveBytes.length)
                    .appUser(appUser) // Встановлюємо власника архіву
                    .build();
            AppDocument savedAppDocument = appDocumentDAO.save(archiveDocument);
            log.info("AppDocument для архіву '{}' успішно збережено, id={}", archiveFileName, savedAppDocument.getId());

            return savedAppDocument;
        } catch (Exception e) {
            log.error("Помилка при збереженні згенерованого архіву '{}' для користувача appUserId={}: {}",
                    archiveFileName, appUser.getId(), e.getMessage(), e);
            // Можна прокинути специфічний виняток, якщо потрібно обробити його вище
            throw new UploadFileException("Помилка збереження згенерованого архіву в БД: " + e.getMessage(), e);
        }
    }
}