package lnu.study.service.impl;

import lnu.study.dao.AppUserDAO;
import lnu.study.dao.RawDataDAO;
import lnu.study.dto.AudioToSendDTO;
import lnu.study.dto.DocumentToSendDTO;
import lnu.study.dto.PhotoToSendDTO;
import lnu.study.entity.AppDocument;
import lnu.study.entity.AppPhoto;
import lnu.study.entity.AppUser;
import lnu.study.entity.RawData;
// import lnu.study.entity.enums.UserState; // Використовується через статичний імпорт
import lnu.study.exceptions.UploadFileException;
import lnu.study.service.*; // Wildcard import
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
import org.telegram.telegrambots.meta.api.objects.Voice; // НОВИЙ КОД: Імпорт для Voice

import java.util.Comparator;

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

    private static final String TARGET_VOICE_CONVERT_FORMAT = "mp3"; // НОВИЙ КОД

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
        if (appUser == null) {
            log.warn("Cannot process text message for update {} as AppUser is null.", update.getUpdateId());
            return;
        }

        var userState = appUser.getState();
        var text = update.getMessage().getText();
        var output = "";

        log.info("Processing text message '{}' from user_id: {}. Current user state: {}", text, appUser.getTelegramUserId(), userState);

        ServiceCommand serviceCommand = ServiceCommand.fromValue(text);

        if (CANCEL.equals(serviceCommand)) {
            output = cancelProcess(appUser);
        } else if (RESEND_EMAIL.equals(serviceCommand)) {
            log.info("Processing /resend_email command for user {}", appUser.getTelegramUserId());
            output = appUserService.resendActivationEmail(appUser);
        } else if (CONVERT_FILE.equals(serviceCommand)) {
            log.info("Processing /convert_file command for user {}", appUser.getTelegramUserId());
            if (!appUser.isActive()) {
                if (WAIT_FOR_EMAIL_STATE.equals(userState)) {
                    output = "Будь ласка, завершіть реєстрацію, активувавши обліковий запис через email, перед конвертацією файлів.";
                } else {
                    output = "Будь ласка, зареєструйтесь (/registration) або активуйте свій обліковий запис, щоб конвертувати файли.";
                }
            } else {
                if (!BASIC_STATE.equals(userState) && !AWAITING_FILE_FOR_CONVERSION.equals(userState)) {
                    log.info("User {} was in state {}. Cancelling previous operation and proceeding with /convert_file.", appUser.getTelegramUserId(), userState);
                    appUser.setState(BASIC_STATE);
                }
                appUser.setState(AWAITING_FILE_FOR_CONVERSION);
                appUserDAO.save(appUser);
                log.info("User {} state set to AWAITING_FILE_FOR_CONVERSION and saved.", appUser.getTelegramUserId());
                // ЗМІНЕНО: Оновлено промпт
                output = "Будь ласка, надішліть файл для конвертації:\n" +
                        "- DOCX (в PDF)\n" +
                        "- Фото (в PNG)\n" +
                        "- Голосове повідомлення (в " + TARGET_VOICE_CONVERT_FORMAT.toUpperCase() + ").";
            }
        } else if (WAIT_FOR_EMAIL_STATE.equals(userState)) {
            if (serviceCommand == null) {
                output = appUserService.setEmail(appUser, text);
            } else {
                // Обробка команд, коли очікується email
                if (START.equals(serviceCommand) || HELP.equals(serviceCommand) || CANCEL.equals(serviceCommand)) {
                    output = processServiceCommand(appUser, text);
                } else if (REGISTRATION.equals(serviceCommand)){
                    output = "Ви вже в процесі реєстрації. Надішліть email або /cancel.";
                } else {
                    output = "Очікую на ваш email. Надішліть його або скасуйте операцію (/cancel).";
                }
            }
        } else if (AWAITING_FILE_FOR_CONVERSION.equals(userState)) {
            if (serviceCommand == null) { // Користувач надіслав текст, а не команду/файл
                output = "Очікую на файл для конвертації (DOCX, фото або голосове). Надішліть файл або /cancel.";
            } else { // Користувач надіслав команду
                output = processServiceCommand(appUser, text);
            }
        } else if (BASIC_STATE.equals(userState)) {
            output = processServiceCommand(appUser, text);
        } else {
            log.warn("Unhandled state: {} for user {} with text: '{}'", userState, appUser.getTelegramUserId(), text);
            output = "Не розумію вас у поточному стані. " + help();
        }

        if (output != null && !output.isEmpty()) {
            sendAnswer(output, update.getMessage().getChatId());
        }
    }

    @Override
    @Transactional
    public void processDocMessage(Update update) {
        saveRawData(update);
        var appUser = findOrSaveAppUser(update);
        if (appUser == null) return;
        log.info("ENTERING processDocMessage for user {}. Current state: {}", appUser.getTelegramUserId(), appUser.getState());

        var chatId = update.getMessage().getChatId();
        var telegramUserId = appUser.getTelegramUserId();
        Message message = update.getMessage();

        if (AWAITING_FILE_FOR_CONVERSION.equals(appUser.getState())) {
            log.info("STATE IS AWAITING_FILE_FOR_CONVERSION (DocMessage) for user {}", telegramUserId);
            if (!appUser.isActive()) {
                sendAnswer("Будь ласка, активуйте свій акаунт перед конвертацією файлів.", chatId);
                return;
            }

            Document document = message.getDocument();
            if (document == null) {
                sendAnswer("Помилка: очікувався документ, але його не знайдено.", chatId); return;
            }

            String originalFileName = document.getFileName();
            String mimeType = document.getMimeType();
            String fileId = document.getFileId();

            boolean isDocx = (originalFileName != null && originalFileName.toLowerCase().endsWith(".docx")) ||
                    (mimeType != null && mimeType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
            boolean isPhotoAsDocument = mimeType != null && mimeType.startsWith("image/");

            if (isDocx || isPhotoAsDocument) { // Існуюча логіка для DOCX та фото як документів
                byte[] fileData;
                try {
                    fileData = fileService.downloadFileAsByteArray(fileId);
                    if (fileData == null || fileData.length == 0) throw new UploadFileException("Файл порожній.");
                    log.info("Downloaded file for conversion: {}, MIME: {}, Size: {}", originalFileName, mimeType, fileData.length);
                } catch (Exception e) {
                    log.error("Failed to download file (doc/photo_doc) for conversion: {}", e.getMessage());
                    sendAnswer("Не вдалося завантажити файл для конвертації. Спробуйте ще раз.", chatId);
                    return;
                }

                final String finalOriginalFileName = originalFileName;
                ByteArrayResource fileResource = new ByteArrayResource(fileData) {
                    @Override
                    public String getFilename() { return finalOriginalFileName; }
                };

                String targetFormat = isDocx ? "pdf" : "png";
                String converterApiEndpoint = isDocx ? "/api/document/convert" : "/api/convert";
                String fileTypeDescription = isDocx ? "Документ" : "Фото (як документ)";

                sendAnswer(fileTypeDescription + " '" + finalOriginalFileName + "' отримано. Конвертую в " + targetFormat.toUpperCase() + "...", chatId);

                try {
                    ResponseEntity<byte[]> response = converterClientService.convertFile(fileResource, finalOriginalFileName, targetFormat, converterApiEndpoint);
                    if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                        byte[] convertedFileData = response.getBody();
                        String baseName = finalOriginalFileName.contains(".") ? finalOriginalFileName.substring(0, finalOriginalFileName.lastIndexOf('.')) : finalOriginalFileName;
                        String convertedFileName = "converted_" + baseName + "." + targetFormat;

                        if (isDocx) {
                            producerService.producerSendDocumentDTO(DocumentToSendDTO.builder()
                                    .chatId(chatId.toString()).documentBytes(convertedFileData).fileName(convertedFileName).caption("Сконвертовано: "+finalOriginalFileName).build());
                        } else {
                            producerService.producerSendPhotoDTO(PhotoToSendDTO.builder()
                                    .chatId(chatId.toString()).photoBytes(convertedFileData).fileName(convertedFileName).caption("Сконвертовано: "+finalOriginalFileName).build());
                        }
                        sendAnswer(fileTypeDescription + " '" + finalOriginalFileName + "' успішно сконвертовано!", chatId);
                    } else {
                        log.error("Conversion failed for {} '{}'. Status: {}", fileTypeDescription, finalOriginalFileName, response.getStatusCode());
                        sendAnswer("Помилка конвертації " + fileTypeDescription.toLowerCase() + ". Статус: " + response.getStatusCode(), chatId);
                    }
                } catch (Exception e) {
                    log.error("Exception during conversion call for {}: {}", finalOriginalFileName, e.getMessage(), e);
                    sendAnswer("Критична помилка сервісу конвертації для " + fileTypeDescription.toLowerCase() + ".", chatId);
                } finally {
                    appUser.setState(BASIC_STATE); appUserDAO.save(appUser);
                }
            } else { // Якщо це документ, але не DOCX і не фото
                sendAnswer("Цей тип документа не підтримується для конвертації. Для аудіо, будь ласка, надішліть голосове повідомлення.", chatId);
                // Не скидаємо стан, дозволяємо спробувати ще раз
            }
        } else { // Не в стані конвертації - обробка як звичайне завантаження документа
            log.info("User {} sent a document (not for conversion).", telegramUserId);
            // ... (існуюча логіка завантаження файлу)
            String permissionError = checkPermissionError(appUser); // Передаємо AppUser
            if (permissionError != null) { sendAnswer(permissionError, chatId); return; }
            try {
                AppDocument doc = fileService.processDoc(message);
                if (doc != null) {
                    String link = fileService.generateLink(doc.getId(), LinkType.GET_DOC);
                    sendAnswer("Документ '" + doc.getDocName() + "' завантажено! Посилання: " + link, chatId);
                } else { sendAnswer("Не вдалося обробити документ.", chatId); }
            } catch (Exception e) {
                log.error("Error processing document for storage: {}", e.getMessage());
                sendAnswer("Помилка при збереженні документа.", chatId);
            }
        }
    }

    @Override
    @Transactional
    public void processPhotoMessage(Update update) {
        // Існуюча логіка для фото залишається практично без змін,
        // оскільки вона вже коректно обробляє конвертацію фото в PNG
        // та завантаження фото.
        saveRawData(update);
        var appUser = findOrSaveAppUser(update);
        if (appUser == null) return;
        log.info("ENTERING processPhotoMessage for user {}. Current state: {}", appUser.getTelegramUserId(), appUser.getState());

        var chatId = update.getMessage().getChatId();
        var telegramUserId = appUser.getTelegramUserId();
        Message message = update.getMessage();

        if (AWAITING_FILE_FOR_CONVERSION.equals(appUser.getState())) {
            log.info("STATE IS AWAITING_FILE_FOR_CONVERSION (PhotoMessage) for user {}", telegramUserId);
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
            String originalFileName = "photo_" + telegramUserId + "_" + System.currentTimeMillis() + ".jpg"; // Фото не мають імені
            byte[] fileData;
            try {
                fileData = fileService.downloadFileAsByteArray(fileId);
                if (fileData == null || fileData.length == 0) throw new UploadFileException("Фото порожнє.");
            } catch (Exception e) {
                log.error("Failed to download photo for conversion: {}", e.getMessage());
                sendAnswer("Не вдалося завантажити фото для конвертації.", chatId); return;
            }

            ByteArrayResource fileResource = new ByteArrayResource(fileData) {
                @Override public String getFilename() { return originalFileName; }
            };
            String targetFormat = "png";
            String converterApiEndpoint = "/api/convert"; // Ендпоінт для фото
            sendAnswer("Фото '" + originalFileName + "' отримано. Конвертую в PNG...", chatId);

            try {
                ResponseEntity<byte[]> response = converterClientService.convertFile(fileResource, originalFileName, targetFormat, converterApiEndpoint);
                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    byte[] convertedFileData = response.getBody();
                    String baseName = originalFileName.substring(0, originalFileName.lastIndexOf('.'));
                    String convertedFileName = "converted_" + baseName + "." + targetFormat;
                    producerService.producerSendPhotoDTO(PhotoToSendDTO.builder()
                            .chatId(chatId.toString()).photoBytes(convertedFileData).fileName(convertedFileName).caption("Сконвертовано: "+originalFileName).build());
                    sendAnswer("Фото '" + originalFileName + "' успішно сконвертовано в PNG!", chatId);
                } else {
                    sendAnswer("Помилка конвертації фото. Статус: " + response.getStatusCode(), chatId);
                }
            } catch (Exception e) {
                log.error("Exception during photo conversion call: {}", e.getMessage(), e);
                sendAnswer("Критична помилка сервісу конвертації для фото.", chatId);
            } finally {
                appUser.setState(BASIC_STATE); appUserDAO.save(appUser);
            }
        } else { // Не для конвертації - звичайне завантаження фото
            log.info("User {} sent a photo (not for conversion).", telegramUserId);
            // ... (існуюча логіка завантаження фото)
            String permissionError = checkPermissionError(appUser); // Передаємо AppUser
            if (permissionError != null) { sendAnswer(permissionError, chatId); return; }
            try {
                AppPhoto photo = fileService.processPhoto(message);
                if (photo != null) {
                    String link = fileService.generateLink(photo.getId(), LinkType.GET_PHOTO);
                    sendAnswer("Фото завантажено! Посилання: " + link, chatId);
                } else { sendAnswer("Не вдалося обробити фото.", chatId); }
            } catch (Exception e) {
                log.error("Error processing photo for storage: {}", e.getMessage());
                sendAnswer("Помилка при збереженні фото.", chatId);
            }
        }
    }

    // НОВИЙ КОД: Обробка голосових повідомлень
    @Transactional
    @Override
    public void processVoiceMessage(Update update) {
        saveRawData(update);
        var appUser = findOrSaveAppUser(update);
        if (appUser == null) {
            log.warn("Cannot process voice message for update {} as AppUser is null.", update.getUpdateId());
            return;
        }
        log.info("ENTERING processVoiceMessage for user {}. Current state: {}", appUser.getTelegramUserId(), appUser.getState());

        var chatId = update.getMessage().getChatId();
        var telegramUserId = appUser.getTelegramUserId();
        Message message = update.getMessage();
        Voice telegramVoice = message.getVoice();

        if (telegramVoice == null) {
            log.warn("Message for user {} was routed to processVoiceMessage, but Voice object is null.", telegramUserId);
            sendAnswer("Очікувалося голосове повідомлення, але воно відсутнє.", chatId);
            return;
        }

        if (AWAITING_FILE_FOR_CONVERSION.equals(appUser.getState())) {
            log.info("STATE IS AWAITING_FILE_FOR_CONVERSION (VoiceMessage) for user {}", telegramUserId);

            if (!appUser.isActive()) {
                sendAnswer("Будь ласка, активуйте свій акаунт перед конвертацією файлів.", chatId);
                return;
            }

            String originalFileName = "voice_" + telegramUserId + "_" + System.currentTimeMillis() + ".ogg"; // Голосові зазвичай .ogg (Opus)
            String fileId = telegramVoice.getFileId();
            byte[] fileData;

            try {
                fileData = fileService.downloadFileAsByteArray(fileId);
                if (fileData == null || fileData.length == 0) {
                    throw new UploadFileException("Завантажені дані голосового повідомлення порожні.");
                }
                log.info("Successfully downloaded voice for conversion: FileID='{}', GeneratedName='{}', Size={}", fileId, originalFileName, fileData.length);
            } catch (Exception e) {
                log.error("Failed to download voice for conversion (fileId: {}) for user {}: {}", fileId, telegramUserId, e.getMessage(), e);
                sendAnswer("Не вдалося завантажити ваше голосове повідомлення для конвертації. Спробуйте ще раз або /cancel.", chatId);
                return;
            }

            final String finalOriginalFileName = originalFileName;
            ByteArrayResource fileResource = new ByteArrayResource(fileData) {
                @Override
                public String getFilename() {
                    return finalOriginalFileName;
                }
            };

            String targetFormat = TARGET_VOICE_CONVERT_FORMAT; // "mp3"
            String converterApiEndpoint = "/api/audio/convert"; // Ендпоінт для аудіо в converter-service

            sendAnswer("Голосове '" + finalOriginalFileName + "' отримано. Конвертую в " + targetFormat.toUpperCase() + "...", chatId);

            try {
                ResponseEntity<byte[]> response = converterClientService.convertAudioFile(fileResource, finalOriginalFileName, targetFormat, converterApiEndpoint);

                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    byte[] convertedFileData = response.getBody();
                    log.info("Voice message '{}' successfully converted to {}. Size: {} bytes.", finalOriginalFileName, targetFormat, convertedFileData.length);

                    String baseName = finalOriginalFileName.substring(0, finalOriginalFileName.lastIndexOf('.'));
                    String convertedFileNameWithExt = "converted_" + baseName + "." + targetFormat;

                    AudioToSendDTO audioDTO = AudioToSendDTO.builder()
                            .chatId(chatId.toString())
                            .audioBytes(convertedFileData)
                            .fileName(convertedFileNameWithExt)
                            .caption("Сконвертоване голосове: " + finalOriginalFileName)
                            .build();
                    producerService.producerSendAudioDTO(audioDTO);
                    sendAnswer("Голосове повідомлення успішно сконвертовано в " + targetFormat.toUpperCase() + "!", chatId);
                } else {
                    String errorDetails = (response.getBody() != null) ? new String(response.getBody()) : "Немає деталей";
                    log.error("Failed to convert voice. Status: {}. Details: {}", response.getStatusCode(), errorDetails);
                    sendAnswer("Помилка конвертації голосового повідомлення. Статус: " + response.getStatusCode(), chatId);
                }
            } catch (Exception e) {
                log.error("Critical exception during voice conversion call: {}", e.getMessage(), e);
                sendAnswer("Критична помилка сервісу конвертації для голосового повідомлення.", chatId);
            } finally {
                appUser.setState(BASIC_STATE);
                appUserDAO.save(appUser);
                log.info("User {} state set back to BASIC_STATE after voice conversion attempt.", telegramUserId);
            }
        } else {
            log.info("User {} sent a voice message, but not in AWAITING_FILE_FOR_CONVERSION state.", telegramUserId);
            // Якщо потрібно, тут можна додати логіку збереження голосового повідомлення (поза конвертацією)
            sendAnswer("Голосове повідомлення отримано, але зараз не очікую файли для конвертації.", chatId);
        }
    }

    // Метод processAudioMessage (для файлів, надісланих як Audio, а не Voice)
    // Для "хардкоду" на голосові, цей метод поки що може просто інформувати.
    @Transactional
    @Override
    public void processAudioMessage(Update update) {
        saveRawData(update);
        var appUser = findOrSaveAppUser(update);
        if (appUser == null) return;
        var chatId = update.getMessage().getChatId();
        log.info("ENTERING processAudioMessage for user {}. Current state: {}", appUser.getTelegramUserId(), appUser.getState());

        if (AWAITING_FILE_FOR_CONVERSION.equals(appUser.getState())) {
            sendAnswer("Для конвертації аудіо, будь ласка, надішліть саме голосове повідомлення.", chatId);
        } else {
            sendAnswer("Аудіофайл отримано, але зараз не очікую файли для конвертації.", chatId);
            // Тут можна додати логіку збереження аудіофайлу, якщо потрібно.
        }
    }

    // Змінив параметр на AppUser для уникнення повторного запиту до БД
    @Transactional(readOnly = true)
    protected String checkPermissionError(AppUser appUser) { // ЗМІНЕНО ПАРАМЕТР
        if (appUser == null) { // Малоймовірно, якщо викликається після findOrSaveAppUser
            log.error("checkPermissionError called with null appUser");
            return "Помилка: не вдалося визначити користувача.";
        }
        var userState = appUser.getState();
        log.debug("Checking permissions for user ID: {}, isActive: {}, state: {}", appUser.getId(), appUser.isActive(), userState);
        if (!appUser.isActive()) {
            log.warn("Permission denied for user {}: not active.", appUser.getTelegramUserId());
            if (WAIT_FOR_EMAIL_STATE.equals(userState)) {
                return "Будь ласка, завершіть реєстрацію, активувавши обліковий запис через email.";
            } else {
                return "Зареєструйтесь (/registration) або активуйте свій обліковий запис.";
            }
        }
        // Дозволяємо тільки з BASIC_STATE або AWAITING_FILE_FOR_CONVERSION для завантаження/конвертації
        // Цю перевірку тепер можна уточнити в кожному processXMessage методі, якщо потрібно
        // if (!BASIC_STATE.equals(userState) && !AWAITING_FILE_FOR_CONVERSION.equals(userState)) {
        //     log.warn("User {} is active but not in a state to send files (current: {}).", appUser.getTelegramUserId(), userState);
        //     return "Скасуйте поточну команду (/cancel) для надсилання файлів.";
        // }
        return null; // Дозвіл надано
    }

    private void sendAnswer(String output, Long chatId) {
        if (output == null || output.isEmpty()) {
            log.warn("Attempted to send null or empty message to chat ID: {}", chatId);
            return;
        }
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(chatId.toString());
        sendMessage.setText(output);
        producerService.producerAnswer(sendMessage);
    }

    private String processServiceCommand(AppUser appUser, String cmd) {
        ServiceCommand serviceCommand = ServiceCommand.fromValue(cmd);
        if (serviceCommand == null) {
            return "Невідома команда! Щоб побачити список команд, введіть /help";
        }
        switch (serviceCommand) {
            case REGISTRATION:
                if (appUser.getState() == WAIT_FOR_EMAIL_STATE || appUser.getState() == EMAIL_CONFIRMED_STATE || appUser.isActive()){
                    return "Ви вже зареєстровані або в процесі реєстрації.";
                }
                return appUserService.registerUser(appUser);
            case HELP:
                return help();
            case START:
                return "Вітаю! Я бот для конвертації та збереження файлів. Введіть /help для списку команд.";
            case CANCEL: // Додано обробку CANCEL тут
                return cancelProcess(appUser);
            default:
                log.warn("Command {} not handled in processServiceCommand for user state {}", serviceCommand, appUser.getState());
                return "Невідома команда. Введіть /help.";
        }
    }

    private String help() {
        // ЗМІНЕНО: Оновлено help
        return "Список доступних команд:\n"
                + "/cancel - скасування поточної команди;\n"
                + "/registration - реєстрація користувача;\n"
                + "/resend_email - повторно надіслати лист активації;\n"
                + "/convert_file - конвертувати файл (DOCX в PDF, фото в PNG, голосове в MP3).";
    }

    @Transactional
    protected String cancelProcess(AppUser appUser) {
        appUser.setState(BASIC_STATE);
        appUserDAO.save(appUser);
        log.info("User {} cancelled operation, state set to BASIC_STATE", appUser.getTelegramUserId());
        return "Команду скасовано! Поточну операцію перервано.";
    }

    @Transactional
    protected AppUser findOrSaveAppUser(Update update) {
        if (update == null || update.getMessage() == null) {
            log.error("Update or message is null in findOrSaveAppUser"); return null;
        }
        User telegramUser = update.getMessage().getFrom();
        if (telegramUser == null) {
            log.error("Update received without user info: {}", update.getUpdateId()); return null;
        }
        return appUserDAO.findByTelegramUserId(telegramUser.getId())
                .orElseGet(() -> {
                    log.info("Creating new AppUser for telegram user_id: {}", telegramUser.getId());
                    AppUser transientAppUser = AppUser.builder()
                            .telegramUserId(telegramUser.getId())
                            .userName(telegramUser.getUserName())
                            .firstName(telegramUser.getFirstName())
                            .lastName(telegramUser.getLastName())
                            .isActive(false)
                            .state(BASIC_STATE)
                            .build();
                    return appUserDAO.save(transientAppUser);
                });
    }

    private void saveRawData(Update update) {
        if (update == null || update.getUpdateId() == null) {
            log.warn("Attempted to save null or invalid Update object."); return;
        }
        RawData rawData = RawData.builder().event(update).build();
        rawDataDAO.save(rawData);
    }
}