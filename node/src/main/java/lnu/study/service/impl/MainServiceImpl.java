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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import java.util.ArrayList;
import java.util.List;

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
                    } else if (isPhotoAsDocument) { // <--- ОСЬ ЦЯ ГІЛКА ЗМІНЮЄТЬСЯ
                        log.info("Photo received as document from user {}. Switching to AWAITING_TARGET_FORMAT_SELECTION. FileID: {}, FileName: {}", appUser.getTelegramUserId(), fileId, finalOriginalFileName);

                        // Зберігаємо fileId документа (який є фото) та originalFileName
                        appUser.setPendingFileId(fileId);
                        appUser.setPendingOriginalFileName(finalOriginalFileName);
                        appUser.setPendingFileType("photo");
                        appUser.setState(AWAITING_TARGET_FORMAT_SELECTION);
                        appUserDAO.save(appUser);

                        sendFormatSelectionMessage(chatId, "photo_document");
                        return; // Дуже важливо завершити тут, ми чекаємо на вибір формату
                    } else if (isVideoAsDocument) { // <--- ОСЬ ЦЯ ГІЛКА ЗМІНЮЄТЬСЯ
                        log.info("Video received as document from user {}. Switching to AWAITING_TARGET_FORMAT_SELECTION. FileID: {}, FileName: {}",
                                appUser.getTelegramUserId(), fileId, finalOriginalFileName);

                        appUser.setPendingFileId(fileId); // fileId документа (який є відео)
                        appUser.setPendingOriginalFileName(finalOriginalFileName);
                        appUser.setPendingFileType("video"); // <--- Встановлюємо тип
                        appUser.setState(AWAITING_TARGET_FORMAT_SELECTION);
                        appUserDAO.save(appUser);

                        sendVideoFormatSelectionMessage(chatId); // <--- Новий метод для вибору формату відео
                        return; // Дуже важливо завершити тут, ми чекаємо на вибір формату
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
            appUser.setPendingFileId(fileId);
            appUser.setPendingOriginalFileName(originalFileName);
            appUser.setPendingFileType("photo");
            appUser.setState(AWAITING_TARGET_FORMAT_SELECTION);
            appUserDAO.save(appUser);

            sendFormatSelectionMessage(chatId, "photo"); // Викликаємо наш новий метод
            log.info("Photo received from user {}. Switched to AWAITING_TARGET_FORMAT_SELECTION. FileID: {}, FileName: {}", appUser.getTelegramUserId(), fileId, originalFileName);
            return; // Важливо завершити виконання тут, оскільки ми чекаємо на відповідь користувача
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
        Message message = update.getMessage();
        Video telegramVideo = message.getVideo();

        if (telegramVideo == null) {
            log.warn("Message for user {} was routed to processVideoMessage, but Video object is null.", appUser.getTelegramUserId());
            sendAnswer("Очікувався відеофайл, але він відсутній.", chatId);
            return;
        }

        if (AWAITING_FILE_FOR_CONVERSION.equals(appUser.getState())) {
            log.info("STATE IS AWAITING_FILE_FOR_CONVERSION (VideoMessage) for user {}", appUser.getTelegramUserId());

            if (!appUser.isActive()) {
                sendAnswer("Будь ласка, активуйте свій акаунт перед конвертацією файлів.", chatId);
                return;
            }

            String originalFileName = telegramVideo.getFileName();
            if (originalFileName == null || originalFileName.isEmpty()) {
                // Проста генерація імені, якщо воно відсутнє
                String mimeType = telegramVideo.getMimeType() != null ? telegramVideo.getMimeType().toLowerCase() : "video/mp4";
                String ext = "mp4"; // Default
                if (mimeType.contains("mp4")) ext = "mp4";
                else if (mimeType.contains("quicktime")) ext = "mov";
                else if (mimeType.contains("x-msvideo")) ext = "avi";
                else if (mimeType.contains("x-matroska")) ext = "mkv";
                else if (mimeType.contains("webm")) ext = "webm";
                originalFileName = "video_" + appUser.getTelegramUserId() + "_" + System.currentTimeMillis() + "." + ext;
                log.info("Generated filename for direct video message: {}", originalFileName);
            }

            String fileId = telegramVideo.getFileId();

            appUser.setPendingFileId(fileId);
            appUser.setPendingOriginalFileName(originalFileName);
            appUser.setPendingFileType("video"); // <--- Встановлюємо тип
            appUser.setState(AWAITING_TARGET_FORMAT_SELECTION);
            appUserDAO.save(appUser);

            sendVideoFormatSelectionMessage(chatId); // <--- Новий метод для вибору формату відео
            log.info("Video received from user {}. Switched to AWAITING_TARGET_FORMAT_SELECTION. FileID: {}, FileName: {}",
                    appUser.getTelegramUserId(), fileId, originalFileName);
            return;

        } else {
            log.info("User {} sent a video message, but not in AWAITING_FILE_FOR_CONVERSION state.", appUser.getTelegramUserId());
            // Тут можна або просто проігнорувати (якщо не очікуємо відео для файлообмінника),
            // або запропонувати перейти в режим конвертації, або обробити як файл для обміну, якщо така логіка є.
            // Поки що залишимо як є - пропонуємо перейти в режим конвертації.
            sendAnswer("Відео отримано, але бот не в режимі конвертації. Використайте /convert_file, щоб увімкнути цей режим, " +
                    "або /generate_link, щоб просто поділитись файлом.", chatId);
        }
    }

    private void sendVideoFormatSelectionMessage(Long chatId) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(chatId.toString());
        sendMessage.setText("Оберіть цільовий формат для конвертації ВІДЕО:");

        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

        // Приклади форматів, обери ті, які підтримуватиме твій VideoConverterController
        List<InlineKeyboardButton> rowInline1 = new ArrayList<>();
        rowInline1.add(InlineKeyboardButton.builder().text("MP4").callbackData("format_select_video_mp4").build());
        rowInline1.add(InlineKeyboardButton.builder().text("MKV").callbackData("format_select_video_mkv").build());

        List<InlineKeyboardButton> rowInline2 = new ArrayList<>();
        rowInline2.add(InlineKeyboardButton.builder().text("WEBM").callbackData("format_select_video_webm").build());
        rowInline2.add(InlineKeyboardButton.builder().text("MOV").callbackData("format_select_video_mov").build());

        // Новий ряд з кнопкою "Скасувати"
        List<InlineKeyboardButton> rowCancel = new ArrayList<>();
        rowCancel.add(InlineKeyboardButton.builder().text("❌ Скасувати вибір").callbackData("cancel_format_selection").build());

        rowsInline.add(rowInline1);
        rowsInline.add(rowInline2);
        rowsInline.add(rowCancel);
        // if (!rowInline3.isEmpty()) rowsInline.add(rowInline3);


        markupInline.setKeyboard(rowsInline);
        sendMessage.setReplyMarkup(markupInline);

        producerService.producerAnswer(sendMessage);
        log.info("Sent video format selection keyboard to chat_id: {}", chatId);
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

    private void sendFormatSelectionMessage(Long chatId, String fileTypeContext) { // fileTypeContext поки не використовуємо, але може знадобитися
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(chatId.toString());
        sendMessage.setText("Оберіть цільовий формат для конвертації фото:");

        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

        List<InlineKeyboardButton> rowInline1 = new ArrayList<>();
        rowInline1.add(InlineKeyboardButton.builder().text("JPG").callbackData("format_select_jpg").build());
        rowInline1.add(InlineKeyboardButton.builder().text("PNG").callbackData("format_select_png").build());

        List<InlineKeyboardButton> rowInline2 = new ArrayList<>();
        rowInline2.add(InlineKeyboardButton.builder().text("BMP").callbackData("format_select_bmp").build());
        rowInline2.add(InlineKeyboardButton.builder().text("WEBP").callbackData("format_select_webp").build());

        // Новий ряд з кнопкою "Скасувати"
        List<InlineKeyboardButton> rowCancel = new ArrayList<>();
        rowCancel.add(InlineKeyboardButton.builder().text("❌ Скасувати вибір").callbackData("cancel_format_selection").build());


        rowsInline.add(rowInline1);
        rowsInline.add(rowInline2);
        rowsInline.add(rowCancel);
        // if (rowInline3.size() > 0) rowsInline.add(rowInline3); // Якщо додали третій ряд

        markupInline.setKeyboard(rowsInline);
        sendMessage.setReplyMarkup(markupInline);

        producerService.producerAnswer(sendMessage);
        log.info("Sent format selection keyboard to chat_id: {} for context: {}", chatId, fileTypeContext);
    }
    // ... інші методи класу ...

    @Transactional
    public void processFormatSelectionCallback(Update update) {
        // Перевіряємо, чи є CallbackQuery і чи є дані в ньому
        if (update == null || !update.hasCallbackQuery() || update.getCallbackQuery().getData() == null) {
            log.warn("Received update in processFormatSelectionCallback without valid CallbackQuery or data.");
            return;
        }

        var callbackQuery = update.getCallbackQuery();
        var chatId = callbackQuery.getMessage().getChatId();
        var appUser = findOrSaveAppUser(update); // findOrSaveAppUser має коректно обробляти User з CallbackQuery
        String callbackData = callbackQuery.getData();

        if (appUser == null) {
            log.warn("AppUser is null for callback query from chat_id: {}", chatId);
            // Можливо, варто відповісти користувачеві, що сталася помилка
            // answerCallbackQuery(callbackQuery.getId(), "Помилка: користувача не знайдено."); // Потрібно буде додати метод answerCallbackQuery
            return;
        }

        log.info("Processing format selection callback for user_id: {}. Chat_id: {}. Callback data: '{}'. Current state: {}",
                appUser.getTelegramUserId(), chatId, callbackData, appUser.getState());
        if ("cancel_format_selection".equals(callbackData)) {
            log.info("User {} cancelled format selection.", appUser.getTelegramUserId());

            // Очищаємо збережені дані про файл
            appUser.setPendingFileId(null);
            appUser.setPendingOriginalFileName(null);
            appUser.setPendingFileType(null);

            // Повертаємо користувача в стан очікування файлу для конвертації
            appUser.setState(AWAITING_FILE_FOR_CONVERSION);
            appUserDAO.save(appUser);

            sendAnswer("Вибір формату скасовано. Можете надіслати інший файл для конвертації, або використати /cancel для виходу з режиму конвертації.", chatId);
        }
            // Перевіряємо, чи користувач у правильному стані і чи дані колбеку відповідають нашим очікуванням
        if (AWAITING_TARGET_FORMAT_SELECTION.equals(appUser.getState())) {
            String pendingFileType = appUser.getPendingFileType(); // Отримуємо тип файлу

            if (callbackData.startsWith("format_select_video_") && "video".equals(pendingFileType)) {
                // ОБРОБКА ВИБОРУ ФОРМАТУ ДЛЯ ВІДЕО
                String targetFormat = callbackData.substring("format_select_video_".length());
                log.info("User selected VIDEO format '{}'. Pending file type: {}", targetFormat, pendingFileType);

                String fileIdForConversion = appUser.getPendingFileId();
                String originalFileNameForConversion = appUser.getPendingOriginalFileName();

                if (fileIdForConversion == null || originalFileNameForConversion == null) {
                    // ... (обробка помилки: файл не знайдено, як для фото, але можна уточнити повідомлення для відео)
                    log.error("Pending VIDEO file ID or original name is null for user {}...", appUser.getTelegramUserId());
                    sendAnswer("Помилка: не можу знайти ВІДЕО, яке ви хотіли конвертувати...", chatId);
                    appUser.setPendingFileId(null);
                    appUser.setPendingOriginalFileName(null);
                    appUser.setPendingFileType(null); // Очищаємо тип
                    appUser.setState(AWAITING_FILE_FOR_CONVERSION);
                    appUserDAO.save(appUser);
                    if (callbackQuery != null && callbackQuery.getId() != null) {
                        producerService.producerAnswerCallbackQuery(callbackQuery.getId(), "Помилка: відео не знайдено");
                    }
                    return;
                }

                byte[] fileData;
                try {
                    fileData = fileService.downloadFileAsByteArray(fileIdForConversion);
                    if (fileData == null || fileData.length == 0) throw new UploadFileException("Завантажений ВІДЕО файл порожній.");
                    log.info("Successfully downloaded pending VIDEO for conversion: FileID='{}', OriginalName='{}', Size={}", fileIdForConversion, originalFileNameForConversion, fileData.length);
                } catch (Exception e) {
                    // ... (обробка помилки завантаження, як для фото, але з уточненим повідомленням)
                    log.error("Failed to download pending VIDEO file_id {} for conversion: {}", fileIdForConversion, e.getMessage(), e);
                    sendAnswer("Не вдалося завантажити ВІДЕО для конвертації...", chatId);
                    appUser.setPendingFileId(null);
                    appUser.setPendingOriginalFileName(null);
                    appUser.setPendingFileType(null);
                    appUser.setState(AWAITING_FILE_FOR_CONVERSION);
                    appUserDAO.save(appUser);
                    if (callbackQuery != null && callbackQuery.getId() != null) {
                        producerService.producerAnswerCallbackQuery(callbackQuery.getId(), "Помилка завантаження відео");
                    }
                    return;
                }

                final String finalOriginalVideoName = originalFileNameForConversion;
                ByteArrayResource videoFileResource = new ByteArrayResource(fileData) {
                    @Override public String getFilename() { return finalOriginalVideoName; }
                };

                String videoConverterApiEndpoint = "/api/video/convert"; // Переконайся, що цей ендпоінт приймає 'format'
                boolean videoConversionSuccess = false;

                sendAnswer("Розпочинаю конвертацію ВІДЕО '" + originalFileNameForConversion + "' у формат " + targetFormat.toUpperCase() + "...", chatId);

                try {
                    ResponseEntity<byte[]> response = converterClientService.convertVideoFile(videoFileResource, originalFileNameForConversion, targetFormat, videoConverterApiEndpoint);

                    if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                        byte[] convertedVideoData = response.getBody();
                        String baseName = originalFileNameForConversion.contains(".") ?
                                originalFileNameForConversion.substring(0, originalFileNameForConversion.lastIndexOf('.')) :
                                originalFileNameForConversion;
                        String convertedVideoFileName = "converted_" + baseName + "." + targetFormat;

                        // Надсилаємо як документ
                        producerService.producerSendDocumentDTO(DocumentToSendDTO.builder()
                                .chatId(chatId.toString())
                                .documentBytes(convertedVideoData)
                                .fileName(convertedVideoFileName)
                                .caption("Сконвертоване відео: " + originalFileNameForConversion + " -> " + targetFormat.toUpperCase())
                                .build());
                        videoConversionSuccess = true;
                        log.info("Successfully converted and sent VIDEO as document '{}' (original: '{}') to format '{}' for user {}",
                                convertedVideoFileName, originalFileNameForConversion, targetFormat, appUser.getTelegramUserId());
                    } else {
                        log.error("VIDEO conversion failed for '{}' to {}. Status: {}.",
                                originalFileNameForConversion, targetFormat, response.getStatusCode());
                        sendAnswer("Помилка конвертації ВІДЕО в " + targetFormat.toUpperCase() + ". Статус: " + response.getStatusCode(), chatId);
                    }
                } catch (Exception e) {
                    log.error("Critical exception during VIDEO conversion for file {}: {}", originalFileNameForConversion, e.getMessage(), e);
                    sendAnswer("Критична помилка сервісу конвертації для вашого ВІДЕО.", chatId);
                } finally {
                    appUser.setPendingFileId(null);
                    appUser.setPendingOriginalFileName(null);
                    appUser.setPendingFileType(null); // Очищаємо тип

                    String callbackResponseMessage;
                    if (videoConversionSuccess) {
                        sendPostConversionMessage(chatId);
                        callbackResponseMessage = "Відео конвертовано в " + targetFormat.toUpperCase() + "!";
                    } else {
                        sendAnswer("Не вдалося сконвертувати ВІДЕО. Спробуйте ще раз.", chatId);
                        callbackResponseMessage = "Помилка конвертації відео";
                    }
                    appUser.setState(AWAITING_FILE_FOR_CONVERSION);
                    appUserDAO.save(appUser);
                    if (callbackQuery != null && callbackQuery.getId() != null) {
                        producerService.producerAnswerCallbackQuery(callbackQuery.getId(), callbackResponseMessage);
                    }
                }

            } else if (callbackData.startsWith("format_select_") && "photo".equals(pendingFileType)) {
                // ОБРОБКА ВИБОРУ ФОРМАТУ ДЛЯ ФОТО (твій існуючий код)
                String targetFormat = callbackData.substring("format_select_".length());
                log.info("User selected PHOTO format '{}'. Pending file type: {}", targetFormat, pendingFileType);

                String fileIdForConversion = appUser.getPendingFileId();
                String originalFileNameForConversion = appUser.getPendingOriginalFileName();

                if (fileIdForConversion == null || originalFileNameForConversion == null) {
                    log.error("Pending file ID or original name is null for user {} in AWAITING_TARGET_FORMAT_SELECTION state.", appUser.getTelegramUserId());
                    sendAnswer("Помилка: не можу знайти файл, який ви хотіли конвертувати. Будь ласка, надішліть його знову.", chatId);
                    // Скидаємо стан і "завислі" дані
                    appUser.setPendingFileId(null);
                    appUser.setPendingOriginalFileName(null);
                    appUser.setState(AWAITING_FILE_FOR_CONVERSION); // Повертаємо до стану очікування файлу
                    appUserDAO.save(appUser);
                    if (callbackQuery != null && callbackQuery.getId() != null) { // Додано
                        producerService.producerAnswerCallbackQuery(callbackQuery.getId(), "Помилка: файл не знайдено");
                    }
                    return;
                }

                log.info("User {} selected format '{}' for file_id '{}', original_name '{}'",
                        appUser.getTelegramUserId(), targetFormat, fileIdForConversion, originalFileNameForConversion);

                byte[] fileData;
                try {
                    // 1. Завантажити fileData за fileIdForConversion
                    fileData = fileService.downloadFileAsByteArray(fileIdForConversion);
                    if (fileData == null || fileData.length == 0) {
                        throw new UploadFileException("Завантажений файл для конвертації порожній або відсутній.");
                    }
                    log.info("Successfully downloaded pending file for conversion: FileID='{}', OriginalName='{}', Size={}",
                            fileIdForConversion, originalFileNameForConversion, fileData.length);
                } catch (Exception e) {
                    log.error("Failed to download pending file_id {} for conversion: {}", fileIdForConversion, e.getMessage(), e);
                    sendAnswer("Не вдалося завантажити файл для конвертації. Спробуйте надіслати його знову.", chatId);
                    // Скидаємо стан і "завислі" дані
                    appUser.setPendingFileId(null);
                    appUser.setPendingOriginalFileName(null);
                    appUser.setState(AWAITING_FILE_FOR_CONVERSION);
                    appUserDAO.save(appUser);
                    appUser.setState(AWAITING_FILE_FOR_CONVERSION);
                    appUserDAO.save(appUser);
                    if (callbackQuery != null && callbackQuery.getId() != null) { // Додано
                        producerService.producerAnswerCallbackQuery(callbackQuery.getId(), "Помилка завантаження файлу");
                    }
                    return;
                }

                // 2. Створити ByteArrayResource
                final String finalOriginalName = originalFileNameForConversion; // для використання в лямбді
                ByteArrayResource fileResource = new ByteArrayResource(fileData) {

                    @Override
                    public String getFilename() {
                        return finalOriginalName;
                    }

                };
                String photoConverterApiEndpoint = "/api/convert";
                boolean photoConversionSuccess = false;

                sendAnswer("Розпочинаю конвертацію ФОТО '" + originalFileNameForConversion + "' у формат " + targetFormat.toUpperCase() + "...", chatId);

                try {
                    // 3. Викликати converterClientService.convertFile(...) з новим targetFormat
                    ResponseEntity<byte[]> response = converterClientService.convertFile(fileResource, originalFileNameForConversion, targetFormat, photoConverterApiEndpoint);

                    // 4. Обробити відповідь, надіслати сконвертований файл
                    if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                        byte[] convertedFileData = response.getBody();
                        String baseName = originalFileNameForConversion.contains(".") ?
                                originalFileNameForConversion.substring(0, originalFileNameForConversion.lastIndexOf('.')) :
                                originalFileNameForConversion;
                        // Переконуємося, що ім'я файлу має правильне розширення
                        String convertedFileNameWithTargetExt = "converted_" + baseName + "." + targetFormat;

                        producerService.producerSendDocumentDTO(DocumentToSendDTO.builder()
                                .chatId(chatId.toString())
                                .documentBytes(convertedFileData)
                                .fileName(convertedFileNameWithTargetExt)
                                .caption("Сконвертоване фото: " + originalFileNameForConversion + " -> " + targetFormat.toUpperCase() +
                                        "\nТип файлу: " + targetFormat.toUpperCase())
                                .build());
                        photoConversionSuccess = true;
                        log.info("Successfully converted and sent PHOTO as document '{}' (original: '{}') to format '{}' for user {}",
                                convertedFileNameWithTargetExt, originalFileNameForConversion, targetFormat, appUser.getTelegramUserId());

                    } else {
                        log.error("PHOTO conversion failed for '{}' to {}. Status: {}. Response body present: {}",
                                originalFileNameForConversion, targetFormat,
                                response.getStatusCode(), response.getBody() != null);
                        sendAnswer("Помилка конвертації ФОТО в " + targetFormat.toUpperCase() + ". Статус: " + response.getStatusCode(), chatId);
                    }
                } catch (Exception e) {
                    log.error("Critical exception during PHOTO conversion call for file {}: {}", originalFileNameForConversion, e.getMessage(), e);
                    sendAnswer("Критична помилка сервісу конвертації для вашого ФОТО.", chatId);
                } finally {
                    // 6. Очистити pending поля
                    appUser.setPendingFileId(null);
                    appUser.setPendingOriginalFileName(null);
                    appUser.setPendingFileType(null); // <--- ОЧИЩЕННЯ ТИПУ ФАЙЛУ ТУТ!

                    String callbackResponseMessage;
                    if (photoConversionSuccess) {
                        // 5. Надіслати повідомлення sendPostConversionMessage(chatId); у разі успіху
                        sendPostConversionMessage(chatId);
                        callbackResponseMessage = "Фото конвертовано в " + targetFormat.toUpperCase() + "!";
                    } else {
                        sendAnswer("Не вдалося сконвертувати ФОТО. Спробуйте ще раз або оберіть інший файл/формат.", chatId);
                        callbackResponseMessage = "Помилка конвертації фото";
                    }

                    // 7. Встановити стан (завжди повертаємо в очікування нового файлу для конвертації в цьому режимі)
                    appUser.setState(AWAITING_FILE_FOR_CONVERSION);
                    // 8. Зберегти appUser
                    appUserDAO.save(appUser);

                    // 9. "Відповісти" на CallbackQuery
                    if (callbackQuery != null && callbackQuery.getId() != null) {
                        producerService.producerAnswerCallbackQuery(callbackQuery.getId(), callbackResponseMessage);
                    }
                }
                // Не забудь очистити appUser.setPendingFileType(null); у блоці finally для фото також.

            } else {
                // Невідомий callbackData або невідповідність pendingFileType
                log.warn("Mismatch or unknown callback_data: '{}' with pendingFileType: '{}' for user {} in state {}",
                        callbackData, pendingFileType, appUser.getTelegramUserId(), appUser.getState());
                sendAnswer("Сталася незрозуміла помилка з вибором формату. Спробуйте знову.", chatId);
                appUser.setPendingFileId(null);
                appUser.setPendingOriginalFileName(null);
                appUser.setPendingFileType(null);
                appUser.setState(AWAITING_FILE_FOR_CONVERSION); // Або BASIC_STATE
                appUserDAO.save(appUser);
                if (callbackQuery != null && callbackQuery.getId() != null) {
                    producerService.producerAnswerCallbackQuery(callbackQuery.getId(), "Помилка обробки");
                }
            }


        } else if (callbackData.startsWith("format_select_")) {
            // Користувач натиснув кнопку вибору формату, але він не в тому стані
            log.warn("User {} (state: {}) pressed format selection button '{}', but not in AWAITING_TARGET_FORMAT_SELECTION state.",
                    appUser.getTelegramUserId(), appUser.getState(), callbackData);
            sendAnswer("Здається, сталася помилка зі станом. Будь ласка, спробуйте надіслати файл для конвертації знову.", chatId);
            // Можна скинути стан до базового або до очікування файлу
            appUser.setState(BASIC_STATE);
            appUserDAO.save(appUser);
            appUser.setState(BASIC_STATE);
            appUserDAO.save(appUser);
            if (callbackQuery != null && callbackQuery.getId() != null) { // Додано
                producerService.producerAnswerCallbackQuery(callbackQuery.getId(), "Помилка стану");
            }
        } else {
            // Інший CallbackQuery, який ми не очікуємо тут
            log.warn("Received unexpected callback_data '{}' from user {} in state {}",
                    callbackData, appUser.getTelegramUserId(), appUser.getState());
            if (callbackQuery != null && callbackQuery.getId() != null) { // Додано
                producerService.producerAnswerCallbackQuery(callbackQuery.getId(), null); // Просто прибрати годинник, без тексту
            }        }
    }
}