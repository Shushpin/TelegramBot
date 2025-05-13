package lnu.study.service.impl;

import lnu.study.dao.AppUserDAO;
import lnu.study.dao.RawDataDAO;
import lnu.study.dto.DocumentToSendDTO; // Імпорт DocumentToSendDTO
import lnu.study.dto.PhotoToSendDTO;
import lnu.study.entity.AppDocument;
import lnu.study.entity.AppPhoto;
import lnu.study.entity.AppUser;
import lnu.study.entity.RawData;
import lnu.study.entity.enums.UserState;
import lnu.study.exceptions.UploadFileException;
import lnu.study.service.AppUserService;
import lnu.study.service.FileService;
import lnu.study.service.MainService;
import lnu.study.service.ProducerService;
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
import lnu.study.service.ConverterClientService;
// org.telegram.telegrambots.meta.api.methods.send.SendDocument; // Не використовується для прямої відправки звідси
// org.telegram.telegrambots.meta.api.methods.send.SendPhoto; // Не використовується для прямої відправки звідси
// org.telegram.telegrambots.meta.api.objects.InputFile; // Не використовується для прямої відправки звідси
// import java.io.ByteArrayInputStream; // Не використовується для прямої відправки звідси

import java.util.Comparator;
import java.util.Optional;


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

    private static final String FILE_RECEIVED_MESSAGE = "Файл отримано! Обробляється...";
    // private static final String CONVERT_FILE_PROMPT = "Будь ласка, надішліть файл, який ви бажаєте конвертувати."; // Вже є в processTextMessage


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
    @Transactional // Додаємо, оскільки є appUserDAO.save(appUser)
    public void processTextMessage(Update update) {
        saveRawData(update);
        var appUser = findOrSaveAppUser(update);
        if (appUser == null) {
            log.warn("Cannot process text message for update {} as AppUser is null.", update.getUpdateId());
            return;
        }
        // log.info("ENTERING processTextMessage for user {}. Current state: {}", appUser.getTelegramUserId(), appUser.getState()); // Лог processDocMessage був тут помилково

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
        }
        else if (CONVERT_FILE.equals(serviceCommand)) {
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
                    // Можна додати sendAnswer(cancelProcess(appUser), update.getMessage().getChatId()); якщо потрібно окреме повідомлення про скасування
                    // Або просто скинути стан:
                    appUser.setState(BASIC_STATE);
                    // appUserDAO.save(appUser); // Збережеться нижче
                }
                appUser.setState(AWAITING_FILE_FOR_CONVERSION);
                appUserDAO.save(appUser);
                log.info("User {} state set to AWAITING_FILE_FOR_CONVERSION and saved.", appUser.getTelegramUserId());
                output = "Будь ласка, надішліть файл (фото або DOCX), який ви бажаєте конвертувати."; // Оновлено промпт
            }
        }
        else if (WAIT_FOR_EMAIL_STATE.equals(userState)) {
            if (serviceCommand == null) {
                log.info("User {} is in WAIT_FOR_EMAIL_STATE, processing text '{}' as email.", appUser.getTelegramUserId(), text);
                output = appUserService.setEmail(appUser, text);
            } else {
                log.warn("User {} sent command '{}' while in WAIT_FOR_EMAIL_STATE.", appUser.getTelegramUserId(), text);
                if (START.equals(serviceCommand) || HELP.equals(serviceCommand)) {
                    output = processServiceCommand(appUser, text);
                } else if (REGISTRATION.equals(serviceCommand)) {
                    output = "Ви вже в процесі реєстрації. Будь ласка, надішліть свій email або введіть /cancel для скасування.";
                } else {
                    output = "Ви перебуваєте в процесі введення email. Будь ласка, надішліть свій email або введіть /cancel для скасування.";
                }
            }
        } else if (BASIC_STATE.equals(userState)) {
            output = processServiceCommand(appUser, text);
        }
        else if (AWAITING_FILE_FOR_CONVERSION.equals(userState)) {
            if (serviceCommand == null) {
                output = "Очікую на файл для конвертації. Будь ласка, надішліть фото або DOCX файл, або введіть /cancel для скасування.";
            } else {
                output = processServiceCommand(appUser, text);
            }
        }
        else {
            if (serviceCommand != null) {
                output = processServiceCommand(appUser, text);
            } else {
                log.warn("User {} is in an unhandled state: {} and sent text: '{}'. Sending default help.", appUser.getTelegramUserId(), userState, text);
                output = "Не розумію вас у поточному стані. " + help();
            }
        }

        // Надсилаємо відповідь тільки якщо output не порожній
        if (output != null && !output.isEmpty()) {
            log.info("Final text output for user_id: {}: '{}'", appUser.getTelegramUserId(), output);
            sendAnswer(output, update.getMessage().getChatId());
        } else {
            log.debug("No direct text output to send for user_id: {}", appUser.getTelegramUserId());
        }
    }

    @Override
    @Transactional
    public void processDocMessage(Update update) {
        saveRawData(update);
        var appUser = findOrSaveAppUser(update);
        if (appUser == null) {
            log.warn("Cannot process doc message for update {} as AppUser is null.", update.getUpdateId());
            return;
        }
        log.info("ENTERING processDocMessage for user {}. Current state: {}", appUser.getTelegramUserId(), appUser.getState());

        var chatId = update.getMessage().getChatId();
        var telegramUserId = appUser.getTelegramUserId();
        Message message = update.getMessage();

        if (AWAITING_FILE_FOR_CONVERSION.equals(appUser.getState())) {
            log.info("STATE IS AWAITING_FILE_FOR_CONVERSION - Proceeding with document conversion logic for user {}", appUser.getTelegramUserId());

            if (!appUser.isActive()) {
                sendAnswer("Будь ласка, активуйте свій акаунт перед конвертацією файлів.", chatId);
                // Не змінюємо стан, щоб користувач міг активуватися і спробувати знову
                return;
            }

            Document document = message.getDocument();
            if (document == null) {
                log.warn("User {} sent a message without a document while in AWAITING_FILE_FOR_CONVERSION state.", telegramUserId);
                sendAnswer("Очікувався документ. Будь ласка, надішліть DOCX файл для конвертації в PDF або скасуйте операцію /cancel.", chatId);
                // Стан не змінюємо, дозволяємо користувачу спробувати ще раз
                return;
            }

            String originalFileName = document.getFileName();
            String mimeType = document.getMimeType();
            String fileId = document.getFileId();
            byte[] fileData;

            // Перевіряємо, чи це DOCX файл
            boolean isDocx = (originalFileName != null && originalFileName.toLowerCase().endsWith(".docx")) ||
                    (mimeType != null && mimeType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));

            // Перевіряємо, чи це фото, надіслане як документ
            boolean isPhotoAsDocument = mimeType != null && mimeType.startsWith("image/");


            if (!isDocx && !isPhotoAsDocument) { // Якщо це НЕ DOCX і НЕ фото
                log.warn("User {} sent an unsupported file type ('{}', MIME: '{}') for conversion.", telegramUserId, originalFileName, mimeType);
                sendAnswer("Підтримуються лише файли DOCX (для конвертації в PDF) та фото (для конвертації в PNG). Будь ласка, надішліть відповідний файл або скасуйте операцію /cancel.", chatId);
                // Не змінюємо стан, дозволяємо спробувати ще
                return;
            }


            try {
                fileData = fileService.downloadFileAsByteArray(fileId);
                if (fileData == null || fileData.length == 0) {
                    throw new UploadFileException("Завантажені дані файлу порожні або відсутні.");
                }
                log.info("Successfully downloaded file for conversion: Name='{}', MIME='{}', Size={}", originalFileName, mimeType, fileData.length);
            } catch (UploadFileException e) {
                log.error("Failed to download file for conversion (fileId: {}) for user {}: {}", fileId, telegramUserId, e.getMessage());
                sendAnswer("Не вдалося завантажити ваш файл для конвертації. Спробуйте ще раз або скасуйте /cancel.", chatId);
                return;
            } catch (Exception e) {
                log.error("Unexpected error downloading file for conversion (fileId: {}) for user {}: {}", fileId, telegramUserId, e.getMessage(), e);
                sendAnswer("Сталася непередбачена помилка під час завантаження вашого файлу. Спробуйте ще раз або скасуйте /cancel.", chatId);
                return;
            }

            String targetFormat;
            String converterApiEndpoint;
            String fileTypeDescription;

            if (isDocx) {
                targetFormat = "pdf";
                converterApiEndpoint = "/api/document/convert";
                fileTypeDescription = "Документ";
            } else { // isPhotoAsDocument
                targetFormat = "png";
                // ЗМІНЕНО: виправляємо ендпоінт для фото
                converterApiEndpoint = "/api/convert"; // <--- ВИПРАВЛЕНО
                fileTypeDescription = "Фото";
                // Якщо ім'я файлу для фото не надано, генеруємо його
                if (originalFileName == null || !originalFileName.toLowerCase().matches(".+\\.(jpg|jpeg|png|gif|bmp|tiff)$")) {
                    originalFileName = "photo_as_doc_" + telegramUserId + "_" + System.currentTimeMillis() + ".jpg"; // Припускаємо jpg, якщо невідомо
                }
            }


            String finalOriginalFileName = originalFileName;
            ByteArrayResource fileResource = new ByteArrayResource(fileData) {
                @Override
                public String getFilename() {
                    return finalOriginalFileName;
                }
            };

            sendAnswer(fileTypeDescription + " '" + originalFileName + "' отримано. Розпочинаю конвертацію в " + targetFormat.toUpperCase() + "...", chatId);

            try {
                ResponseEntity<byte[]> response = converterClientService.convertFile(fileResource, originalFileName, targetFormat, converterApiEndpoint);

                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    byte[] convertedFileData = response.getBody();
                    log.info("{} '{}' successfully converted to {}. Size: {} bytes. Preparing to send to user...", fileTypeDescription, originalFileName, targetFormat, convertedFileData.length);

                    String baseName = originalFileName.contains(".") ? originalFileName.substring(0, originalFileName.lastIndexOf('.')) : originalFileName;
                    String convertedFileName = "converted_" + baseName + "." + targetFormat;

                    if (isDocx) {
                        DocumentToSendDTO documentDTO = DocumentToSendDTO.builder()
                                .chatId(chatId.toString())
                                .documentBytes(convertedFileData)
                                .fileName(convertedFileName)
                                .build();
                        producerService.producerSendDocumentDTO(documentDTO);
                        log.info("Sent DocumentToSendDTO to producer for chat_id: {}", chatId);
                    } else { // isPhotoAsDocument
                        PhotoToSendDTO photoDTO = PhotoToSendDTO.builder()
                                .chatId(chatId.toString())
                                .photoBytes(convertedFileData)
                                .fileName(convertedFileName)
                                .build();
                        producerService.producerSendPhotoDTO(photoDTO);
                        log.info("Sent PhotoToSendDTO (from document) to producer for chat_id: {}", chatId);
                    }

                    // ТІЛЬКИ ОДНЕ ПОВІДОМЛЕННЯ ПРО УСПІХ
                    sendAnswer(fileTypeDescription + " '" + originalFileName + "' успішно сконвертовано в " + targetFormat.toUpperCase() + " та поставлено в чергу на відправку!", chatId);

                } else {
                    String errorDetails = response.getBody() != null ? new String(response.getBody()) : "Немає деталей";
                    log.error("Failed to convert {} '{}'. Status: {}. Details: {}", fileTypeDescription.toLowerCase(), originalFileName, response.getStatusCode(), errorDetails);
                    sendAnswer("На жаль, сталася помилка під час конвертації " + fileTypeDescription.toLowerCase() + " '" + originalFileName + "'. Спробуйте інший файл.", chatId);
                }
            } catch (Exception e) {
                log.error("Exception during call to converterService for {} '{}': {}", fileTypeDescription.toLowerCase(), originalFileName, e.getMessage(), e);
                sendAnswer("Критична помилка сервісу конвертації для " + fileTypeDescription.toLowerCase() + " '" + originalFileName + "'.", chatId);
            } finally {
                appUser.setState(BASIC_STATE);
                appUserDAO.save(appUser);
                log.info("User {} state set back to BASIC_STATE after attempting file conversion.", telegramUserId);
            }
            return;
        }
        else {
            log.info("User {} sent a document, but not in AWAITING_FILE_FOR_CONVERSION state. Processing as regular document upload.", telegramUserId);
            String permissionError = checkPermissionError(telegramUserId);
            if (permissionError != null) {
                sendAnswer(permissionError, chatId);
                return;
            }
            // Видалено sendAnswer(FILE_RECEIVED_MESSAGE, chatId); щоб уникнути дублювання
            try {
                AppDocument doc = fileService.processDoc(message);
                if (doc != null) {
                    String link = fileService.generateLink(doc.getId(), LinkType.GET_DOC);
                    var answer = "Документ успішно завантажено! " // Змінено, щоб не дублювати "Файл отримано"
                            + "Посилання для скачування: " + link;
                    sendAnswer(answer, chatId);
                } else {
                    log.error("Document processing returned null for update: {}", update.getUpdateId());
                    sendAnswer("Не вдалося обробити документ. Спробуйте пізніше.", chatId);
                }
            } catch (UploadFileException ex) {
                log.error("UploadFileException occurred during doc processing: ", ex);
                String error = "На жаль, завантаження файлу не вдалося. Спробуйте пізніше.";
                sendAnswer(error, chatId);
            } catch (Exception e) {
                log.error("Exception occurred during doc processing: ", e);
                String error = "Сталася непередбачена помилка під час обробки файлу.";
                sendAnswer(error, chatId);
            }
        }
    }

    @Override
    @Transactional // Додано @Transactional
    public void processPhotoMessage(Update update) {
        saveRawData(update);
        var appUser = findOrSaveAppUser(update);
        if (appUser == null) {
            log.warn("Cannot process photo message for update {} as AppUser is null.", update.getUpdateId());
            return;
        }
        log.info("ENTERING processPhotoMessage for user {}. Current state: {}", appUser.getTelegramUserId(), appUser.getState());

        var chatId = update.getMessage().getChatId();
        var telegramUserId = appUser.getTelegramUserId();
        Message message = update.getMessage();

        if (AWAITING_FILE_FOR_CONVERSION.equals(appUser.getState())) {
            log.info("STATE IS AWAITING_FILE_FOR_CONVERSION - Proceeding with photo conversion logic for user {}", appUser.getTelegramUserId());

            if (!appUser.isActive()) {
                sendAnswer("Будь ласка, активуйте свій акаунт перед конвертацією файлів.", chatId);
                return;
            }

            if (message.getPhoto() == null || message.getPhoto().isEmpty()) {
                log.warn("User {} sent a message without a photo while in AWAITING_FILE_FOR_CONVERSION state.", telegramUserId);
                sendAnswer("Очікувалося фото. Будь ласка, надішліть фото для конвертації в PNG або скасуйте операцію /cancel.", chatId);
                return;
            }

            PhotoSize photoSize = message.getPhoto().stream()
                    .max(Comparator.comparing(ps -> ps.getFileSize() != null ? ps.getFileSize() : 0))
                    .orElse(null);

            if (photoSize == null) {
                log.error("No photo data found for conversion for user {}", telegramUserId);
                sendAnswer("Не вдалося отримати дані фото для конвертації.", chatId);
                return;
            }

            String fileId = photoSize.getFileId();
            String originalFileName = "photo_" + telegramUserId + "_" + System.currentTimeMillis() + ".jpg"; // Припускаємо jpg
            byte[] fileData;

            try {
                fileData = fileService.downloadFileAsByteArray(fileId);
                if (fileData == null || fileData.length == 0) {
                    throw new UploadFileException("Завантажені дані фото порожні або відсутні.");
                }
                log.info("Successfully downloaded photo for conversion: FileID='{}', OriginalName='{}', Size={}", fileId, originalFileName, fileData.length);
            } catch (UploadFileException e) {
                log.error("Failed to download photo for conversion (fileId: {}) for user {}: {}", fileId, telegramUserId, e.getMessage());
                sendAnswer("Не вдалося завантажити ваше фото для конвертації. Спробуйте ще раз або скасуйте /cancel.", chatId);
                return;
            } catch (Exception e) {
                log.error("Unexpected error downloading photo for conversion (fileId: {}) for user {}: {}", fileId, telegramUserId, e.getMessage(), e);
                sendAnswer("Сталася непередбачена помилка під час завантаження вашого фото. Спробуйте ще раз або скасуйте /cancel.", chatId);
                return;
            }

            String targetFormat = "png";
            String converterApiEndpoint = "/api/convert";

            ByteArrayResource fileResource = new ByteArrayResource(fileData) {
                @Override
                public String getFilename() {
                    return originalFileName;
                }
            };

            sendAnswer("Фото '" + originalFileName + "' отримано. Розпочинаю конвертацію в " + targetFormat.toUpperCase() + "...", chatId);

            try {
                ResponseEntity<byte[]> response = converterClientService.convertFile(fileResource, originalFileName, targetFormat, converterApiEndpoint);

                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    byte[] convertedFileData = response.getBody();
                    log.info("Photo '{}' successfully converted to {}. Size: {} bytes. Preparing to send to user...", originalFileName, targetFormat, convertedFileData.length);

                    String baseName = originalFileName.contains(".") ? originalFileName.substring(0, originalFileName.lastIndexOf('.')) : originalFileName;
                    String convertedFileName = "converted_" + baseName + "." + targetFormat;

                    PhotoToSendDTO photoDTO = PhotoToSendDTO.builder()
                            .chatId(chatId.toString())
                            .photoBytes(convertedFileData)
                            .fileName(convertedFileName)
                            .build();

                    producerService.producerSendPhotoDTO(photoDTO);
                    log.info("Sent PhotoToSendDTO to producer for chat_id: {}", chatId);

                    // ТІЛЬКИ ОДНЕ ПОВІДОМЛЕННЯ ПРО УСПІХ
                    sendAnswer("Фото '" + originalFileName + "' успішно сконвертовано в " + targetFormat.toUpperCase() + " та поставлено в чергу на відправку!", chatId);

                } else {
                    String errorDetails = response.getBody() != null ? new String(response.getBody()) : "Немає деталей";
                    log.error("Failed to convert photo '{}'. Status: {}. Details: {}", originalFileName, response.getStatusCode(), errorDetails);
                    sendAnswer("На жаль, сталася помилка під час конвертації фото '" + originalFileName + "'.", chatId);
                }
            } catch (Exception e) {
                log.error("Exception during call to converterService for photo '{}': {}", originalFileName, e.getMessage(), e);
                sendAnswer("Критична помилка сервісу конвертації для фото '" + originalFileName + "'.", chatId);
            } finally {
                appUser.setState(BASIC_STATE);
                appUserDAO.save(appUser);
                log.info("User {} state set back to BASIC_STATE after attempting photo conversion.", telegramUserId);
            }
            return;
        }
        else {
            log.info("User {} sent a photo, but not in AWAITING_FILE_FOR_CONVERSION state. Processing as regular photo upload.", telegramUserId);
            String permissionError = checkPermissionError(telegramUserId);
            if (permissionError != null) {
                sendAnswer(permissionError, chatId);
                return;
            }
            // Видалено sendAnswer(FILE_RECEIVED_MESSAGE, chatId);
            try {
                AppPhoto photo = fileService.processPhoto(message);
                if (photo != null) {
                    String link = fileService.generateLink(photo.getId(), LinkType.GET_PHOTO);
                    var answer = "Фото успішно завантажено! " // Змінено
                            + "Посилання для скачування: " + link;
                    sendAnswer(answer, chatId);
                } else {
                    log.error("Photo processing returned null for update: {}", update.getUpdateId());
                    sendAnswer("Не вдалося обробити фото. Спробуйте пізніше.", chatId);
                }
            } catch (UploadFileException ex) {
                log.error("UploadFileException occurred during photo processing: ", ex);
                String error = "На жаль, завантаження фото не вдалося. Спробуйте пізніше.";
                sendAnswer(error, chatId);
            } catch (Exception e) {
                log.error("Exception occurred during photo processing: ", e);
                String error = "Сталася непередбачена помилка під час обробки фото.";
                sendAnswer(error, chatId);
            }
        }
    }

    private String removeExtension(String filename) {
        if (filename == null) return "converted-file";
        int lastDot = filename.lastIndexOf('.');
        if (lastDot == -1) {
            return filename;
        }
        return filename.substring(0, lastDot);
    }

    @Transactional(readOnly = true)
    protected String checkPermissionError(Long telegramUserId) {
        if (telegramUserId == null) {
            log.error("checkPermissionError called with null telegramUserId");
            return "Помилка: не вдалося визначити користувача.";
        }
        Optional<AppUser> optionalAppUser = appUserDAO.findByTelegramUserId(telegramUserId);
        if (optionalAppUser.isEmpty()) {
            log.error("User not found in checkPermissionError for telegramUserId: {}", telegramUserId);
            return "Помилка: користувач не знайдений.";
        }
        AppUser appUser = optionalAppUser.get();
        var userState = appUser.getState();
        log.debug("Checking permissions for user ID: {}, isActive: {}, state: {}", appUser.getId(), appUser.isActive(), userState);
        if (!appUser.isActive()) {
            log.warn("Permission denied for user {}: not active.", telegramUserId);
            if (WAIT_FOR_EMAIL_STATE.equals(userState)) {
                return "Будь ласка, завершіть реєстрацію, активувавши обліковий запис через email, перед завантаженням файлів.";
            } else {
                return "Зареєструйтесь (/registration) або активуйте "
                        + "свій обліковий запис для завантаження контенту.";
            }
        }
        else if (!BASIC_STATE.equals(userState) && !AWAITING_FILE_FOR_CONVERSION.equals(userState)) {
            log.warn("Permission denied for user {}: is active but not in BASIC_STATE or AWAITING_FILE_FOR_CONVERSION (current state: {}).", telegramUserId, userState);
            return "Скасуйте поточну команду за допомогою /cancel для надсилання файлів.";
        }
        log.debug("Permission granted for user {}", telegramUserId);
        return null;
    }

    private void sendAnswer(String output, Long chatId) {
        if (output == null || output.isEmpty()) {
            log.warn("Attempted to send null or empty message to chat ID: " + chatId);
            return;
        }
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(chatId.toString());
        sendMessage.setText(output);
        producerService.producerAnswer(sendMessage);
    }

    private String processServiceCommand(AppUser appUser, String cmd) {
        var serviceCommand = ServiceCommand.fromValue(cmd);
        if (serviceCommand == null) {
            // Дозволяємо /help, /start з будь-якого стану, якщо це не спеціальна обробка
            log.debug("Received non-command text '{}' from user {} in state {}", cmd, appUser.getTelegramUserId(), appUser.getState());
            return "Невідома команда! Щоб переглянути список доступних команд, введіть /help";
        }
        switch (serviceCommand) {
            case REGISTRATION:
                if (WAIT_FOR_EMAIL_STATE.equals(appUser.getState()) || EMAIL_CONFIRMED_STATE.equals(appUser.getState())) {
                    return "Ви вже в процесі реєстрації або ваш email підтверджено. Якщо хочете змінити email, зверніться до підтримки.";
                }
                return appUserService.registerUser(appUser);
            case HELP:
                return help();
            case START:
                return "Вітаю! Щоб переглянути список доступних команд, введіть /help";
            default:
                log.warn("Command {} recognized but not handled in processServiceCommand for user state {}", serviceCommand, appUser.getState());
                return "Невідома команда! Щоб переглянути список доступних команд, введіть /help";
        }
    }

    private String help() {
        return "Список доступних команд:\n"
                + "/cancel - скасування виконання поточної команди;\n"
                + "/registration - реєстрація користувача;\n"
                + "/resend_email - повторно надіслати лист активації (якщо ви в процесі реєстрації);\n"
                + "/convert_file - розпочати процес конвертації файлу (фото в PNG, DOCX в PDF).";
    }

    @Transactional
    protected String cancelProcess(AppUser appUser) { // Змінено на protected, якщо не використовується поза пакетом
        appUser.setState(BASIC_STATE);
        appUserDAO.save(appUser);
        log.info("User {} cancelled operation, state set to BASIC_STATE", appUser.getTelegramUserId());
        return "Команду скасовано!";
    }

    @Transactional // Додано @Transactional
    protected AppUser findOrSaveAppUser(Update update) { // Змінено на protected
        if (update == null || update.getMessage() == null) {
            log.error("Update or message is null in findOrSaveAppUser");
            return null;
        }
        User telegramUser = update.getMessage().getFrom();
        if (telegramUser == null) {
            log.error("Update received without user info: {}", update.getUpdateId());
            return null;
        }
        Optional<AppUser> optionalAppUser = appUserDAO.findByTelegramUserId(telegramUser.getId());
        if (optionalAppUser.isEmpty()) {
            log.info("Creating new AppUser for telegram user_id: {}", telegramUser.getId());
            AppUser transientAppUser = AppUser.builder()
                    .telegramUserId(telegramUser.getId())
                    .userName(telegramUser.getUserName())
                    .firstName(telegramUser.getFirstName())
                    .lastName(telegramUser.getLastName())
                    .isActive(false) // За замовчуванням неактивний
                    .state(BASIC_STATE)
                    .build();
            return appUserDAO.save(transientAppUser);
        } else {
            AppUser existingUser = optionalAppUser.get();
            log.debug("Found existing AppUser with ID: {} for telegram user_id: {}", existingUser.getId(), telegramUser.getId());
            return existingUser;
        }
    }

    private void saveRawData(Update update) {
        if (update == null || update.getUpdateId() == null) {
            log.warn("Attempted to save null or invalid Update object.");
            return;
        }
        RawData rawData = RawData.builder()
                .event(update)
                .build();
        rawDataDAO.save(rawData);
    }
}