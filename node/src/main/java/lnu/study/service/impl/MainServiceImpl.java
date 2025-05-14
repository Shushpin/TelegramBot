package lnu.study.service.impl;

import lnu.study.dao.AppUserDAO;
import lnu.study.dao.RawDataDAO;
import lnu.study.dto.AudioToSendDTO;
import lnu.study.dto.DocumentToSendDTO;
import lnu.study.dto.PhotoToSendDTO;
import lnu.study.dto.VideoToSendDTO;
import lnu.study.entity.AppDocument;
import lnu.study.entity.AppPhoto;
import lnu.study.entity.AppUser;
import lnu.study.entity.RawData;
import lnu.study.exceptions.UploadFileException;
import lnu.study.service.*;
import lnu.study.service.enums.LinkType;
import lnu.study.service.enums.ServiceCommand;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.Video;
import org.telegram.telegrambots.meta.api.objects.Voice;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;


import static lnu.study.entity.enums.UserState.*;
import static lnu.study.service.enums.ServiceCommand.*;

@Log4j2
@Service
public class MainServiceImpl implements MainService {
    private final RawDataDAO rawDataDAO;
    private final ProducerService producerService;
    private final AppUserDAO appUserDAO;
    private final FileService fileService;
    private final AppUserService appUserService;
    private final ConverterClientService converterClientService;

    private static final String TARGET_VOICE_CONVERT_FORMAT = "mp3";
    private static final String TARGET_VIDEO_CONVERT_FORMAT = "mp4";

    private static final List<String> SUPPORTED_VIDEO_MIME_TYPES = Arrays.asList(
            "video/mp4", "video/quicktime", "video/x-msvideo", "video/x-matroska", "video/webm", "video/3gpp", "video/x-flv"
    );


    public MainServiceImpl(RawDataDAO rawDataDAO,
                           ProducerService producerService,
                           AppUserDAO appUserDAO,
                           FileService fileService, AppUserService appUserService, ConverterClientService converterClientService) {
        this.rawDataDAO = rawDataDAO;
        this.producerService = producerService;
        this.appUserDAO = appUserDAO;
        this.fileService = fileService;
        this.appUserService = appUserService;
        this.converterClientService = converterClientService;
    }

    @Override
    @Transactional
    public void processTextMessage(Update update) {
        saveRawData(update);
        var appUser = findOrSaveAppUser(update);
        if (appUser == null) return;

        var userState = appUser.getState();
        var text = update.getMessage().getText();
        var output = "";
        var chatId = update.getMessage().getChatId();
        log.info("Processing text message '{}' from user_id: {}. Current user state: {}", text, appUser.getTelegramUserId(), userState);
        ServiceCommand serviceCommand = ServiceCommand.fromValue(text);

        // Обробка команд, які змінюють стан або доступні в будь-якому стані (якщо це має сенс)
        if (CANCEL.equals(serviceCommand)) {
            output = cancelProcess(appUser);
        } else if (GENERATE_LINK.equals(serviceCommand)) { // НОВА ОБРОБКА
            output = switchToGenerateLinkMode(appUser);
        } else if (HELP.equals(serviceCommand)) { // /help доступна завжди
            output = help();
        }
        // Логіка залежно від стану
        else if (CONVERT_FILE.equals(serviceCommand)) {
            if (!appUser.isActive()) {
                output = "Будь ласка, зареєструйтесь (/registration) та активуйте обліковий запис для конвертації.";
            } else {
                appUser.setState(AWAITING_FILE_FOR_CONVERSION);
                appUserDAO.save(appUser);
                output = "Ви в режимі конвертації. Надішліть файл для обробки:\n" +
                        "- DOCX (в PDF)\n" +
                        "- Фото (в PNG)\n" +
                        "- Голосове (в " + TARGET_VOICE_CONVERT_FORMAT.toUpperCase() + ")\n" +
                        "- Відео (в " + TARGET_VIDEO_CONVERT_FORMAT.toUpperCase() + ").\n\n" +
                        "Для виходу з режиму конвертації: /cancel\n" +
                        "Для переходу в режим файлообмінника: /generate_link";
            }
        } else if (AWAITING_FILE_FOR_CONVERSION.equals(userState)) {
            // У режимі конвертації, якщо прийшов текст, це або невідома команда, або просто текст.
            // /cancel та /generate_link вже оброблені вище. /help також.
            if (serviceCommand != null && !HELP.equals(serviceCommand)) { // Інша команда, крім вже оброблених
                output = "Ви знаходитесь в режимі конвертації. " +
                        "Надішліть файл, або використайте /cancel чи /generate_link.";
            } else if (serviceCommand == null) { // Просто текст
                output = "Будь ласка, надішліть файл для конвертації, або використайте /cancel чи /generate_link.";
            }
            // Якщо output для /help залишився порожнім, він заповниться вище
        } else if (WAIT_FOR_EMAIL_STATE.equals(userState)) {
            if (serviceCommand == null) { // Очікуємо email
                output = appUserService.setEmail(appUser, text);
            } else { // Інша команда в стані очікування email
                output = "Будь ласка, введіть ваш email або використайте /cancel для скасування реєстрації.";
            }
        } else { // BASIC_STATE або EMAIL_CONFIRMED_STATE (після активації)
            if (START.equals(serviceCommand)) {
                output = "Вітаю! Я ваш помічник для роботи з файлами. Використовуйте /help для перегляду команд.";
            } else if (REGISTRATION.equals(serviceCommand)) {
                output = appUserService.registerUser(appUser); // appUserService має обробити випадок повторної реєстрації
            } else if (RESEND_EMAIL.equals(serviceCommand)) {
                output = appUserService.resendActivationEmail(appUser);
            } else if (serviceCommand == null && (BASIC_STATE.equals(userState) || EMAIL_CONFIRMED_STATE.equals(userState))) {
                // Якщо це не команда, а користувач в базовому стані (або щойно активував email)
                output = "Невідома дія. Надішліть файл для створення посилання або використайте /help для списку команд.";
            } else if (serviceCommand != null && !HELP.equals(serviceCommand)) { // Невідома команда, крім /help
                output = "Невідома команда. Використайте /help.";
            }
            // Якщо output для /help залишився порожнім, він заповниться вище
        }

        if (output != null && !output.isEmpty()) {
            sendAnswer(output, chatId);
        }
    }

    // Новий метод для переходу в режим генерації посилань
    private String switchToGenerateLinkMode(AppUser appUser) {
        appUser.setState(BASIC_STATE);
        appUserDAO.save(appUser);
        log.info("User {} switched to BASIC_STATE (generate link mode).", appUser.getTelegramUserId());
        return "Режим конвертації вимкнено. Тепер ви можете надсилати файли для генерації посилань.\n" +
                "Для повторного входу в режим конвертації використайте /convert_file.";
    }

    // Новий допоміжний метод для повідомлення після конвертації
    private void sendPostConversionMessage(Long chatId) {
        String messageText = "Файл успішно сконвертовано!\n" +
                "Надішліть наступний файл для конвертації, \n" +
                "або /cancel для виходу з режиму, \n" +
                "або /generate_link для переходу в режим файлообмінника.";
        sendAnswer(messageText, chatId);
    }


    @Override
    @Transactional
    public void processDocMessage(Update update) {
        saveRawData(update);
        var appUser = findOrSaveAppUser(update);
        if (appUser == null) return;
        log.info("ENTERING processDocMessage for user {}. Current state: {}", appUser.getTelegramUserId(), appUser.getState());

        var chatId = update.getMessage().getChatId();
        Message message = update.getMessage();

        if (AWAITING_FILE_FOR_CONVERSION.equals(appUser.getState())) {
            if (!appUser.isActive()) {
                sendAnswer("Будь ласка, активуйте акаунт.", chatId); return;
            }
            Document document = message.getDocument();
            if (document == null) {
                sendAnswer("Помилка: очікувався документ.", chatId); return;
            }

            String originalFileName = document.getFileName();
            String mimeType = document.getMimeType() != null ? document.getMimeType().toLowerCase() : "";
            String fileId = document.getFileId();

            boolean isDocx = (originalFileName != null && originalFileName.toLowerCase().endsWith(".docx")) ||
                    mimeType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            boolean isPhotoAsDocument = mimeType.startsWith("image/");
            boolean isVideoAsDocument = mimeType.startsWith("video/") || SUPPORTED_VIDEO_MIME_TYPES.contains(mimeType);

            if (isDocx || isPhotoAsDocument || isVideoAsDocument) {
                byte[] fileData;
                try {
                    fileData = fileService.downloadFileAsByteArray(fileId);
                    if (fileData == null || fileData.length == 0) throw new UploadFileException("Файл порожній.");
                    log.info("Downloaded file (doc/photo/video) for conversion: {}, MIME: {}, Size: {}", originalFileName, mimeType, fileData.length);
                } catch (Exception e) {
                    log.error("Failed to download file for conversion: {}", e.getMessage());
                    sendAnswer("Не вдалося завантажити файл. Спробуйте ще.", chatId); return;
                }

                if ((isPhotoAsDocument || isVideoAsDocument) && (originalFileName == null || originalFileName.isEmpty() || !originalFileName.contains("."))) {
                    String prefix = isPhotoAsDocument ? "photo_doc" : "video_doc";
                    String ext = isPhotoAsDocument ? (mimeType.contains("png") ? "png" : "jpg") : (mimeType.contains("mp4") ? "mp4" : "avi");
                    originalFileName = prefix + "_" + appUser.getTelegramUserId() + "_" + System.currentTimeMillis() + "." + ext;
                    log.info("Generated filename for media sent as document: {}", originalFileName);
                }
                final String finalOriginalFileName = originalFileName;

                ByteArrayResource fileResource = new ByteArrayResource(fileData) {
                    @Override public String getFilename() { return finalOriginalFileName; }
                };

                String targetFormat = null;
                String converterApiEndpoint = null;
                String fileTypeDescription = null;
                ResponseEntity<byte[]> response = null;
                boolean conversionSuccess = false;

                sendAnswer("Файл '" + finalOriginalFileName + "' отримано. Розпочинаю конвертацію...", chatId);

                try {
                    if (isDocx) {
                        targetFormat = "pdf";
                        converterApiEndpoint = "/api/document/convert";
                        fileTypeDescription = "Документ";
                        response = converterClientService.convertFile(fileResource, finalOriginalFileName, targetFormat, converterApiEndpoint);
                    } else if (isPhotoAsDocument) {
                        targetFormat = "png";
                        converterApiEndpoint = "/api/convert";
                        fileTypeDescription = "Фото (як документ)";
                        response = converterClientService.convertFile(fileResource, finalOriginalFileName, targetFormat, converterApiEndpoint);
                    } else if (isVideoAsDocument) {
                        targetFormat = TARGET_VIDEO_CONVERT_FORMAT;
                        converterApiEndpoint = "/api/video/convert";
                        fileTypeDescription = "Відео (як документ)";
                        response = converterClientService.convertVideoFile(fileResource, finalOriginalFileName, targetFormat, converterApiEndpoint);
                    }

                    if (response != null && response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                        byte[] convertedFileData = response.getBody();
                        String baseName = finalOriginalFileName.contains(".") ? finalOriginalFileName.substring(0, finalOriginalFileName.lastIndexOf('.')) : finalOriginalFileName;
                        String convertedFileName = "converted_" + baseName + "." + targetFormat;

                        if (isDocx) {
                            producerService.producerSendDocumentDTO(DocumentToSendDTO.builder()
                                    .chatId(chatId.toString()).documentBytes(convertedFileData).fileName(convertedFileName).caption("Сконвертовано: "+finalOriginalFileName).build());
                        } else if (isPhotoAsDocument) {
                            producerService.producerSendPhotoDTO(PhotoToSendDTO.builder()
                                    .chatId(chatId.toString()).photoBytes(convertedFileData).fileName(convertedFileName).caption("Сконвертовано: "+finalOriginalFileName).build());
                        } else if (isVideoAsDocument) {
                            VideoToSendDTO videoDTO = VideoToSendDTO.builder()
                                    .chatId(chatId.toString()).videoBytes(convertedFileData).fileName(convertedFileName)
                                    .caption("Сконвертоване відео: " + finalOriginalFileName).build();
                            producerService.producerSendVideoDTO(videoDTO);
                        }
                        // sendAnswer(fileTypeDescription + " '" + finalOriginalFileName + "' успішно сконвертовано!", chatId); // Повідомлення перенесено в sendPostConversionMessage
                        conversionSuccess = true;
                    } else {
                        log.error("Conversion failed for {} '{}'. Status: {}", fileTypeDescription, finalOriginalFileName, response != null ? response.getStatusCode() : "N/A");
                        sendAnswer("Помилка конвертації " + (fileTypeDescription != null ? fileTypeDescription.toLowerCase() : "файлу") + ". " + (response != null ? "Статус: " + response.getStatusCode() : ""), chatId);
                    }
                } catch (Exception e) {
                    log.error("Exception during conversion call for {}: {}", finalOriginalFileName, e.getMessage(), e);
                    sendAnswer("Критична помилка сервісу конвертації для " + (fileTypeDescription != null ? fileTypeDescription.toLowerCase() : "файлу") + ".", chatId);
                } finally {
                    // ВАЖЛИВО: Стан НЕ змінюється тут, якщо була конвертація
                    if (conversionSuccess) {
                        sendPostConversionMessage(chatId);
                    }
                    // Якщо сталася помилка ДО або ПІД ЧАС конвертації, користувач залишається в AWAITING_FILE_FOR_CONVERSION,
                    // щоб спробувати ще раз або скасувати. Якщо це небажано, можна додати логіку зміни стану при помилці.
                }
            } else {
                sendAnswer("Цей тип документа не підтримується для конвертації. Очікую DOCX, фото, або відео.", chatId);
                sendPostConversionMessage(chatId); // Нагадуємо про можливість надіслати інший файл
            }
        } else {
            log.info("User {} sent a document (not for conversion).", appUser.getTelegramUserId());
            String permissionError = checkPermissionError(appUser);
            if (permissionError != null) { sendAnswer(permissionError, chatId); return; }
            try {
                AppDocument doc = fileService.processDoc(message);
                if (doc != null) {
                    String link = fileService.generateLink(doc.getId(), LinkType.GET_DOC);
                    sendAnswer("Документ '" + doc.getDocName() + "' завантажено! Посилання: " + link, chatId);
                } else { sendAnswer("Не вдалося обробити документ.", chatId); }
            } catch (Exception e) { sendAnswer("Помилка при збереженні документа.", chatId); }
        }
    }

    @Override
    @Transactional
    public void processPhotoMessage(Update update) {
        saveRawData(update);
        var appUser = findOrSaveAppUser(update);
        if (appUser == null) return;
        log.info("ENTERING processPhotoMessage for user {}. Current state: {}", appUser.getTelegramUserId(), appUser.getState());

        var chatId = update.getMessage().getChatId();
        Message message = update.getMessage();

        if (AWAITING_FILE_FOR_CONVERSION.equals(appUser.getState())) {
            log.info("STATE IS AWAITING_FILE_FOR_CONVERSION (PhotoMessage) for user {}", appUser.getTelegramUserId());
            if (!appUser.isActive()) {
                sendAnswer("Будь ласка, активуйте свій акаунт.", chatId); return;
            }
            if (message.getPhoto() == null || message.getPhoto().isEmpty()) {
                sendAnswer("Очікувалося фото.", chatId); return;
            }

            PhotoSize photoSize = message.getPhoto().stream().max(Comparator.comparing(PhotoSize::getFileSize)).orElse(null);
            if (photoSize == null) {
                sendAnswer("Не вдалося отримати дані фото.", chatId); return;
            }

            String fileId = photoSize.getFileId();
            String originalFileName = "photo_" + appUser.getTelegramUserId() + "_" + System.currentTimeMillis() + ".jpg"; // Можна взяти з photoSize, якщо є
            byte[] fileData;
            try {
                fileData = fileService.downloadFileAsByteArray(fileId);
                if (fileData == null || fileData.length == 0) throw new UploadFileException("Фото порожнє.");
            } catch (Exception e) {
                sendAnswer("Не вдалося завантажити фото для конвертації.", chatId); return;
            }

            ByteArrayResource fileResource = new ByteArrayResource(fileData) {
                @Override public String getFilename() { return originalFileName; }
            };
            String targetFormat = "png";
            String converterApiEndpoint = "/api/convert";
            boolean conversionSuccess = false;
            sendAnswer("Фото '" + originalFileName + "' отримано. Конвертую в PNG...", chatId);

            try {
                ResponseEntity<byte[]> response = converterClientService.convertFile(fileResource, originalFileName, targetFormat, converterApiEndpoint);
                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    byte[] convertedFileData = response.getBody();
                    String baseName = originalFileName.contains(".") ? originalFileName.substring(0, originalFileName.lastIndexOf('.')) : originalFileName;
                    String convertedFileName = "converted_" + baseName + "." + targetFormat;
                    producerService.producerSendPhotoDTO(PhotoToSendDTO.builder()
                            .chatId(chatId.toString()).photoBytes(convertedFileData).fileName(convertedFileName).caption("Сконвертовано: "+originalFileName).build());
                    // sendAnswer("Фото '" + originalFileName + "' успішно сконвертовано в PNG!", chatId); // Перенесено
                    conversionSuccess = true;
                } else {
                    sendAnswer("Помилка конвертації фото. Статус: " + response.getStatusCode(), chatId);
                }
            } catch (Exception e) {
                sendAnswer("Критична помилка сервісу конвертації для фото.", chatId);
            } finally {
                if (conversionSuccess) {
                    sendPostConversionMessage(chatId);
                }
                // Стан не змінюємо, залишаємо AWAITING_FILE_FOR_CONVERSION
            }
        } else {
            log.info("User {} sent a photo (not for conversion).", appUser.getTelegramUserId());
            String permissionError = checkPermissionError(appUser);
            if (permissionError != null) { sendAnswer(permissionError, chatId); return; }
            try {
                AppPhoto photo = fileService.processPhoto(message);
                if (photo != null) {
                    String link = fileService.generateLink(photo.getId(), LinkType.GET_PHOTO);
                    sendAnswer("Фото завантажено! Посилання: " + link, chatId);
                } else { sendAnswer("Не вдалося обробити фото.", chatId); }
            } catch (Exception e) { sendAnswer("Помилка при збереженні фото.", chatId); }
        }
    }

    @Override
    @Transactional
    public void processVoiceMessage(Update update) {
        saveRawData(update);
        var appUser = findOrSaveAppUser(update);
        if (appUser == null) return;
        log.info("ENTERING processVoiceMessage for user {}. Current state: {}", appUser.getTelegramUserId(), appUser.getState());
        var chatId = update.getMessage().getChatId();
        Message message = update.getMessage();
        Voice telegramVoice = message.getVoice();

        if (telegramVoice == null) {
            sendAnswer("Очікувалося голосове, але воно відсутнє.", chatId); return;
        }

        if (AWAITING_FILE_FOR_CONVERSION.equals(appUser.getState())) {
            if (!appUser.isActive()) {
                sendAnswer("Активуйте акаунт.", chatId); return;
            }
            String originalFileName = "voice_" + appUser.getTelegramUserId() + "_" + System.currentTimeMillis() + ".ogg";
            String fileId = telegramVoice.getFileId();
            byte[] fileData;
            try {
                fileData = fileService.downloadFileAsByteArray(fileId);
                if (fileData == null || fileData.length == 0) throw new UploadFileException("Голосове порожнє.");
            } catch (Exception e) {
                sendAnswer("Не вдалося завантажити голосове. /cancel", chatId); return;
            }
            ByteArrayResource fileResource = new ByteArrayResource(fileData) {
                @Override public String getFilename() { return originalFileName; }
            };
            String targetFormat = TARGET_VOICE_CONVERT_FORMAT;
            String converterApiEndpoint = "/api/audio/convert";
            boolean conversionSuccess = false;
            sendAnswer("Голосове '" + originalFileName + "' отримано. Конвертую в " + targetFormat.toUpperCase() + "...", chatId);
            try {
                ResponseEntity<byte[]> response = converterClientService.convertAudioFile(fileResource, originalFileName, targetFormat, converterApiEndpoint);
                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    byte[] convertedFileData = response.getBody();
                    String baseName = originalFileName.contains(".") ? originalFileName.substring(0, originalFileName.lastIndexOf('.')) : originalFileName;
                    String convertedFileName = "converted_" + baseName + "." + targetFormat;
                    producerService.producerSendAudioDTO(AudioToSendDTO.builder()
                            .chatId(chatId.toString()).audioBytes(convertedFileData).fileName(convertedFileName).caption("Сконвертоване: " + originalFileName).build());
                    // sendAnswer("Голосове успішно сконвертовано в " + targetFormat.toUpperCase() + "!", chatId); // Перенесено
                    conversionSuccess = true;
                } else {
                    sendAnswer("Помилка конвертації голосового. Статус: " + response.getStatusCode(), chatId);
                }
            } catch (Exception e) {
                sendAnswer("Критична помилка сервісу конвертації голосового.", chatId);
            } finally {
                if (conversionSuccess) {
                    sendPostConversionMessage(chatId);
                }
                // Стан не змінюємо
            }
        } else {
            sendAnswer("Голосове отримано, але бот не в режимі конвертації. Використайте /convert_file, щоб увімкнути цей режим.", chatId);
        }
    }

    @Override
    @Transactional
    public void processVideoMessage(Update update) {
        saveRawData(update);
        var appUser = findOrSaveAppUser(update);
        if (appUser == null) {
            log.warn("Cannot process video message for update {} as AppUser is null.", update.getUpdateId());
            return;
        }
        log.info("ENTERING processVideoMessage for user {}. Current state: {}", appUser.getTelegramUserId(), appUser.getState());

        var chatId = update.getMessage().getChatId();
        var telegramUserId = appUser.getTelegramUserId();
        Message message = update.getMessage();
        Video telegramVideo = message.getVideo();

        if (telegramVideo == null) {
            log.warn("Message for user {} was routed to processVideoMessage, but Video object is null.", telegramUserId);
            sendAnswer("Очікувався відеофайл, але він відсутній.", chatId);
            return;
        }

        if (AWAITING_FILE_FOR_CONVERSION.equals(appUser.getState())) {
            log.info("STATE IS AWAITING_FILE_FOR_CONVERSION (VideoMessage) for user {}", telegramUserId);

            if (!appUser.isActive()) {
                sendAnswer("Будь ласка, активуйте свій акаунт перед конвертацією файлів.", chatId);
                return;
            }

            String originalFileName = telegramVideo.getFileName();
            if (originalFileName == null || originalFileName.isEmpty()) {
                String mimeType = telegramVideo.getMimeType() != null ? telegramVideo.getMimeType().toLowerCase() : "video/mp4";
                String ext = "mp4";
                if (mimeType.contains("mp4")) ext = "mp4";
                else if (mimeType.contains("quicktime")) ext = "mov";
                else if (mimeType.contains("x-msvideo")) ext = "avi";
                else if (mimeType.contains("x-matroska")) ext = "mkv";
                else if (mimeType.contains("webm")) ext = "webm";
                originalFileName = "video_" + telegramUserId + "_" + System.currentTimeMillis() + "." + ext;
                log.info("Generated filename for direct video message: {}", originalFileName);
            }

            String fileId = telegramVideo.getFileId();
            byte[] fileData;

            try {
                fileData = fileService.downloadFileAsByteArray(fileId);
                if (fileData == null || fileData.length == 0) {
                    throw new UploadFileException("Завантажені дані відео порожні або відсутні.");
                }
                log.info("Successfully downloaded video for conversion: FileID='{}', OriginalName='{}', Size={}", fileId, originalFileName, fileData.length);
            } catch (Exception e) {
                log.error("Failed to download video for conversion (fileId: {}) for user {}: {}", fileId, telegramUserId, e.getMessage(), e);
                sendAnswer("Не вдалося завантажити ваше відео для конвертації. Спробуйте ще раз або /cancel.", chatId);
                return;
            }

            final String finalOriginalFileName = originalFileName;
            ByteArrayResource fileResource = new ByteArrayResource(fileData) {
                @Override public String getFilename() { return finalOriginalFileName; }
            };

            String targetFormat = TARGET_VIDEO_CONVERT_FORMAT;
            String converterApiEndpoint = "/api/video/convert";
            String fileTypeDescription = "Відео";
            boolean conversionSuccess = false;

            sendAnswer(fileTypeDescription + " '" + finalOriginalFileName + "' отримано. Конвертую в " + targetFormat.toUpperCase() + "...", chatId);

            try {
                ResponseEntity<byte[]> response = converterClientService.convertVideoFile(fileResource, finalOriginalFileName, targetFormat, converterApiEndpoint);

                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    byte[] convertedFileData = response.getBody();
                    log.info("Video '{}' successfully converted to {}. Size: {} bytes.", finalOriginalFileName, targetFormat, convertedFileData.length);

                    String baseName = finalOriginalFileName.contains(".") ? finalOriginalFileName.substring(0, finalOriginalFileName.lastIndexOf('.')) : finalOriginalFileName;
                    String convertedFileNameWithExt = "converted_" + baseName + "." + targetFormat;

                    VideoToSendDTO videoDTO = VideoToSendDTO.builder()
                            .chatId(chatId.toString()).videoBytes(convertedFileData).fileName(convertedFileNameWithExt)
                            .caption("Сконвертоване відео: " + finalOriginalFileName)
                            .duration(telegramVideo.getDuration()).width(telegramVideo.getWidth()).height(telegramVideo.getHeight())
                            .build();
                    producerService.producerSendVideoDTO(videoDTO);
                    // sendAnswer(fileTypeDescription + " '" + finalOriginalFileName + "' успішно сконвертовано в " + targetFormat.toUpperCase() + "!", chatId); // Перенесено
                    conversionSuccess = true;
                } else {
                    String errorDetails = (response.getBody() != null) ? new String(response.getBody()) : "Немає деталей";
                    log.error("Failed to convert video. Status: {}. Details: {}", response.getStatusCode(), errorDetails);
                    sendAnswer("Помилка конвертації відео. Статус: " + response.getStatusCode(), chatId);
                }
            } catch (Exception e) {
                log.error("Critical exception during video conversion call: {}", e.getMessage(), e);
                sendAnswer("Критична помилка сервісу конвертації для відео.", chatId);
            } finally {
                if (conversionSuccess) {
                    sendPostConversionMessage(chatId);
                }
                // Стан не змінюємо
                // log.info("User {} state set back to BASIC_STATE after video conversion attempt.", telegramUserId); // Цей лог тепер неактуальний
            }
        } else {
            log.info("User {} sent a video message, but not in AWAITING_FILE_FOR_CONVERSION state.", telegramUserId);
            sendAnswer("Відео отримано, але бот не в режимі конвертації. Використайте /convert_file, щоб увімкнути цей режим.", chatId);
        }
    }

    @Override
    @Transactional
    public void processAudioMessage(Update update) {
        saveRawData(update);
        var appUser = findOrSaveAppUser(update);
        if (appUser == null) return;
        var chatId = update.getMessage().getChatId();
        Message message = update.getMessage();
        org.telegram.telegrambots.meta.api.objects.Audio telegramAudio = message.getAudio();

        if (telegramAudio == null) {
            sendAnswer("Очікувався аудіофайл, але він відсутній.", chatId); return;
        }

        log.info("ENTERING processAudioMessage for user {}. File: {}, MIME: {}, Duration: {}", appUser.getTelegramUserId(), telegramAudio.getFileName(), telegramAudio.getMimeType(), telegramAudio.getDuration());

        if (AWAITING_FILE_FOR_CONVERSION.equals(appUser.getState())) {
            if (!appUser.isActive()) {
                sendAnswer("Будь ласка, активуйте свій акаунт.", chatId); return;
            }

            String originalFileName = telegramAudio.getFileName();
            if (originalFileName == null || originalFileName.isEmpty()) {
                originalFileName = "audio_" + appUser.getTelegramUserId() + "_" + System.currentTimeMillis() + "." + (telegramAudio.getMimeType() != null && telegramAudio.getMimeType().contains("ogg") ? "ogg" : "mp3");
            }
            String fileId = telegramAudio.getFileId();
            byte[] fileData;

            try {
                fileData = fileService.downloadFileAsByteArray(fileId);
                if (fileData == null || fileData.length == 0) throw new UploadFileException("Аудіофайл порожній.");
            } catch (Exception e) {
                sendAnswer("Не вдалося завантажити аудіофайл. /cancel", chatId); return;
            }

            String finalOriginalFileName = originalFileName;
            ByteArrayResource fileResource = new ByteArrayResource(fileData) {
                @Override public String getFilename() { return finalOriginalFileName; }
            };

            String targetFormat = TARGET_VOICE_CONVERT_FORMAT;
            String converterApiEndpoint = "/api/audio/convert";
            String fileTypeDescription = "Аудіофайл";
            boolean conversionSuccess = false;

            sendAnswer(fileTypeDescription + " '" + originalFileName + "' отримано. Конвертую в " + targetFormat.toUpperCase() + "...", chatId);

            try {
                ResponseEntity<byte[]> response = converterClientService.convertAudioFile(fileResource, originalFileName, targetFormat, converterApiEndpoint);
                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    byte[] convertedFileData = response.getBody();
                    String baseName = originalFileName.contains(".") ? originalFileName.substring(0, originalFileName.lastIndexOf('.')) : originalFileName;
                    String convertedFileName = "converted_" + baseName + "." + targetFormat;
                    producerService.producerSendAudioDTO(AudioToSendDTO.builder()
                            .chatId(chatId.toString()).audioBytes(convertedFileData).fileName(convertedFileName).caption("Сконвертовано: " + originalFileName).build());
                    // sendAnswer(fileTypeDescription + " '" + originalFileName + "' успішно сконвертовано!", chatId); // Перенесено
                    conversionSuccess = true;
                } else {
                    sendAnswer("Помилка конвертації аудіофайлу. Статус: " + response.getStatusCode(), chatId);
                }
            } catch (Exception e) {
                sendAnswer("Критична помилка сервісу конвертації аудіофайлу.", chatId);
            } finally {
                if (conversionSuccess) {
                    sendPostConversionMessage(chatId);
                }
                // Стан не змінюємо
            }
        } else {
            sendAnswer("Аудіофайл отримано, але бот не в режимі конвертації. Використайте /convert_file, щоб увімкнути цей режим.", chatId);
        }
    }

    @Transactional(readOnly = true)
    protected String checkPermissionError(AppUser appUser) {
        if (appUser == null) return "Помилка: користувач не визначений.";
        if (!appUser.isActive()) {
            return WAIT_FOR_EMAIL_STATE.equals(appUser.getState()) ?
                    "Завершіть реєстрацію (активація email)." :
                    "Зареєструйтесь (/registration) або активуйте акаунт.";
        }
        return null;
    }

    private void sendAnswer(String output, Long chatId) {
        if (output == null || output.isEmpty()) return;
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(chatId.toString());
        sendMessage.setText(output);
        producerService.producerAnswer(sendMessage);
    }

    private String help() {
        return "Доступні команди:\n"
//                + "/start - початок роботи\n"
                + "/help - допомога\n"
                + "/cancel - скасувати поточну дію\n"
                + "/registration - реєстрація\n"
//                + "/resend_email - повторно надіслати лист активації\n"
                + "/convert_file - увімкнути режим конвертації файлів\n"
                + "/generate_link - перейти в режим файлообмінника (вимкнути конвертацію)";
    }

    @Transactional
    protected String cancelProcess(AppUser appUser) {
        String previousStateInfo = "";
        if (AWAITING_FILE_FOR_CONVERSION.equals(appUser.getState())) {
            previousStateInfo = "Режим конвертації скасовано. ";
        }
        appUser.setState(BASIC_STATE);
        appUserDAO.save(appUser);
        log.info("User {} cancelled current operation. State set to BASIC_STATE", appUser.getTelegramUserId());
        return previousStateInfo + "Ви повернулися в основний режим. Можете надсилати файли для обміну або використати /convert_file.";
    }

    @Transactional
    protected AppUser findOrSaveAppUser(Update update) {
        Message message = update.getMessage();
        if (message == null) { // Може бути callback_query, але тут ми очікуємо message
            log.warn("Update {} does not contain a message.", update.getUpdateId());
            if (update.getCallbackQuery() != null) {
                User telegramUser = update.getCallbackQuery().getFrom();
                return appUserDAO.findByTelegramUserId(telegramUser.getId())
                        .orElseGet(() -> appUserDAO.save(AppUser.builder()
                                .telegramUserId(telegramUser.getId())
                                .userName(telegramUser.getUserName())
                                .firstName(telegramUser.getFirstName())
                                .lastName(telegramUser.getLastName())
                                .isActive(false)
                                .state(BASIC_STATE)
                                .build()));
            }
            return null;
        }
        User telegramUser = message.getFrom();
        if (telegramUser == null) {
            log.warn("Message {} does not contain a user.", message.getMessageId());
            return null;
        }
        return appUserDAO.findByTelegramUserId(telegramUser.getId())
                .orElseGet(() -> appUserDAO.save(AppUser.builder()
                        .telegramUserId(telegramUser.getId())
                        .userName(telegramUser.getUserName())
                        .firstName(telegramUser.getFirstName())
                        .lastName(telegramUser.getLastName())
                        .isActive(false)
                        .state(BASIC_STATE)
                        .build()));
    }

    private void saveRawData(Update update) {
        if (update == null || update.getUpdateId() == null) return;
        rawDataDAO.save(RawData.builder().event(update).build());
    }
}