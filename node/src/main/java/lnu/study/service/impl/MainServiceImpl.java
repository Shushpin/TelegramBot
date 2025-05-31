package lnu.study.service.impl;

import lnu.study.dao.AppUserDAO;
import lnu.study.dao.RawDataDAO;
import lnu.study.dto.AudioToSendDTO;
import lnu.study.dto.DocumentToSendDTO;
import lnu.study.dto.PhotoToSendDTO;
import lnu.study.dto.VideoToSendDTO;
import lnu.study.entity.*;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lnu.study.dto.ArchiveFileDetailDTO; // <--- НАШ НОВИЙ DTO
import java.util.Map;                       // <--- Для ConcurrentHashMap
import java.util.concurrent.ConcurrentHashMap; // <--- Для ConcurrentHashMap
import java.util.Arrays;
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


    private static final String TARGET_VOICE_CONVERT_FORMAT = "mp3";
    private static final String TARGET_VIDEO_CONVERT_FORMAT = "mp4";

    private static final List<String> SUPPORTED_VIDEO_MIME_TYPES = Arrays.asList(
            "video/mp4", "video/quicktime", "video/x-msvideo", "video/x-matroska", "video/webm", "video/3gpp", "video/x-flv"
    );
    private final Map<Long, List<ArchiveFileDetailDTO>> archivingSessions = new ConcurrentHashMap<>(); // <--- НОВЕ ПОЛЕ


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
    // Метод для очищення сесії архівування
    private void clearArchiveSession(Long appUserId) {
        if (appUserId == null) {
            log.warn("Спроба очистити сесію архівування для null appUserId.");
            return;
        }
        List<ArchiveFileDetailDTO> removedSession = archivingSessions.remove(appUserId);
        if (removedSession != null) {
            log.info("Сесію архівування для користувача appUserId={} очищено. Видалено {} файлів з сесії.", appUserId, removedSession.size());
        } else {
            log.info("Для користувача appUserId={} не знайдено активної сесії архівування для очищення.", appUserId);
        }
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

        // Якщо користувач вже у стані архівування файлів
        if (ARCHIVING_FILES.equals(userState)) {
            if (CREATE_ARCHIVE.equals(serviceCommand)) { // Користувач повторно надсилає /create_archive
                output = "Ви вже у режимі створення архіву. Будь ласка, надішліть файл або натисніть відповідну кнопку на клавіатурі (буде додано пізніше).";
                sendAnswer(output, chatId); // Використовуй твій метод sendAnswer
                return;
            } else if (CANCEL.equals(serviceCommand)) { // Користувач надсилає /cancel
                output = cancelProcess(appUser); // cancelProcess має викликати clearArchiveSession
                sendAnswer(output, chatId);
                return;
            } else if (serviceCommand != null) { // Користувач надсилає іншу команду
                output = "Ви зараз у режимі створення архіву. Щоб вийти, надішліть /cancel. " +
                        "Інші команди недоступні. Будь ласка, надішліть файл.";
                sendAnswer(output, chatId);
                return;
            } else { // Користувач надсилає текст, а не команду чи файл
                output = "Будь ласка, надішліть файл для архіву або використайте команду /cancel для скасування.";
                sendAnswer(output, chatId);
                return;
            }
        }
        // Обробка команд, які змінюють стан або доступні в будь-якому стані (якщо це має сенс)
        if (CANCEL.equals(serviceCommand)) {
            output = cancelProcess(appUser);
        } else if (GENERATE_LINK.equals(serviceCommand)) { // НОВА ОБРОБКА
            output = switchToGenerateLinkMode(appUser);
        } else if (HELP.equals(serviceCommand)) { // /help доступна завжди
            output = help();
        }else if (CREATE_ARCHIVE.equals(serviceCommand)) {
            if (!appUser.isActive()) { // Перевірка, чи користувач активний
                output = "Будь ласка, зареєструйтесь (/registration) та активуйте обліковий запис для створення архівів.";
            } else {
                clearArchiveSession(appUser.getId()); // Очистити попередню сесію, якщо була
                archivingSessions.put(appUser.getId(), new ArrayList<>()); // Ініціалізувати нову сесію
                appUser.setState(ARCHIVING_FILES); // Встановлюємо новий стан
                appUserDAO.save(appUser); // Зберігаємо зміни стану користувача
                log.info("User {} (appUserId={}) switched to ARCHIVING_FILES state.", appUser.getTelegramUserId(), appUser.getId());
                output = "Розпочато сесію створення архіву. Надішліть перший файл або кілька файлів.\n" +
                        "Для завершення та створення архіву буде відповідна кнопка (після надсилання файлу).\n" +
                        "Для скасування сесії використайте /cancel.";
            }
        }
        // Логіка залежно від стану
        else if (CONVERT_FILE.equals(serviceCommand)) {
            if (!appUser.isActive()) {
                output = "Будь ласка, зареєструйтесь (/registration) та активуйте обліковий запис для конвертації.";
            } else {
                appUser.setState(AWAITING_FILE_FOR_CONVERSION);
                appUserDAO.save(appUser);
                output = "Ви в режимі конвертації. Надішліть файл для обробки:\n" +
                        "- Фото\n" +
                        "- Відео\n" +
                        "- Голосове або аудіо файл\n" +
                        "- DOCX (в PDF чи ODT,конвертація може зайняти до 5 хвилин) \n\n" +
                        "Для виходу з режиму конвертації: /cancel\n" +
                        "Для переходу в режим архіватора: /create_archive\n" +
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
        return "Останній режим вимкнено. Тепер ви можете надсилати файли для генерації посилань.\n\n" +
                "Для перегляду доступних комад використайте /help.";
    }

    // Новий допоміжний метод для повідомлення після конвертації
    private void sendPostConversionMessage(Long chatId) {
        String messageText = "Файл успішно сконвертовано!\n" +
                "Надішліть наступний файл для конвертації, \n" +
                "або /cancel для виходу з режиму, \n" +
                "або /generate_link для переходу в режим файлообмінника, \n" +
                "або /create_archive для переходу в режим архіватора.";
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
        // ОБРОБКА ДОКУМЕНТІВ ДЛЯ АРХІВУВАННЯ
        if (ARCHIVING_FILES.equals(appUser.getState())) {
            Message currentMessage = update.getMessage(); // Отримуємо поточне повідомлення
            Document document = currentMessage.getDocument();

            if (document != null) {
                String fileId = document.getFileId();
                String originalFileName = document.getFileName();
                if (originalFileName == null || originalFileName.isEmpty()) {
                    originalFileName = "document_" + fileId; // Базове ім'я, якщо оригінальне відсутнє
                }

                ArchiveFileDetailDTO fileDetail = new ArchiveFileDetailDTO(fileId, originalFileName, "document");

                // Додаємо файл до сесії користувача. appUser.getId() - це ID з нашої БД.
                List<ArchiveFileDetailDTO> userArchiveFiles = archivingSessions.computeIfAbsent(appUser.getId(), k -> new ArrayList<>());
                userArchiveFiles.add(fileDetail);
                log.info("Додано документ '{}' (file_id: {}) до сесії архівування для користувача appUserId={}",
                        originalFileName, fileId, appUser.getId());

                // Надсилаємо повідомлення з кнопками
                sendArchiveOptions(chatId, "Файл '" + originalFileName + "' отримано.");
            } else {
                // Це малоймовірно, якщо Telegram правильно маршрутизував як DocMessage, але для повноти
                log.warn("Очікувався документ для архівування від користувача appUserId={}, але він відсутній.", appUser.getId());
                sendAnswer("Помилка: очікувався документ, але його не знайдено. Спробуйте надіслати ще раз.", chatId);
            }
            return; // Важливо завершити обробку тут, щоб не виконувалася стандартна логіка збереження/конвертації
        }

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
                        log.info("DOCX received from user {}. FileID: {}, FileName: {}. Switching to AWAITING_TARGET_FORMAT_SELECTION for 'document'.",
                                appUser.getTelegramUserId(), fileId, originalFileName);

                        appUser.setPendingFileId(fileId);
                        appUser.setPendingOriginalFileName(originalFileName);
                        appUser.setPendingFileType("document"); // Встановлюємо тип "document"
                        appUser.setState(AWAITING_TARGET_FORMAT_SELECTION);
                        appUserDAO.save(appUser);

                        sendDocumentFormatSelectionMessage(chatId); // Наш новий метод для показу клавіатури
                        return; // Важливо завершити тут, очікуємо вибір формату
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
                    String outputMessage = "Документ '" + doc.getDocName() + "'завантажено. Посилання: " + link
                            + "\n\nДля виходу з режиму генерації посилань натисніть /cancel або відправте наступний файл.";
                    sendAnswer(outputMessage, chatId);
                } else { sendAnswer("Не вдалося обробити документ.", chatId); }
            } catch (Exception e) { sendAnswer("Помилка при збереженні документа.", chatId); }
        }
    }

    private void sendArchiveOptions(Long chatId, String precedingText) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(chatId.toString());
        sendMessage.setText(precedingText + "\n\nНадішліть наступний файл для архіву або створіть архів з вже відправлених файлів.");

        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

        // Перший ряд кнопок
        List<InlineKeyboardButton> rowMainButtons = new ArrayList<>();
        InlineKeyboardButton addMoreButton = new InlineKeyboardButton();
        addMoreButton.setText("➕ Надіслати ще файл");
        addMoreButton.setCallbackData("ARCHIVE_ADD_MORE");

        InlineKeyboardButton createArchiveButton = new InlineKeyboardButton();
        createArchiveButton.setText("✅ Створити архів");
        createArchiveButton.setCallbackData("ARCHIVE_CREATE_NOW");

        rowMainButtons.add(addMoreButton);
        rowMainButtons.add(createArchiveButton);
        rowsInline.add(rowMainButtons); // Додаємо перший ряд

        // Другий ряд для кнопки "Скасувати"
        List<InlineKeyboardButton> rowCancelButton = new ArrayList<>(); // <--- НОВИЙ РЯД
        InlineKeyboardButton cancelArchiveButton = new InlineKeyboardButton(); // <--- НОВА КНОПКА
        cancelArchiveButton.setText("❌ Скасувати сесію"); // <--- ТЕКСТ КНОПКИ
        cancelArchiveButton.setCallbackData("ARCHIVE_CANCEL_SESSION");    // <--- НОВІ CALLBACK-ДАНІ

        rowCancelButton.add(cancelArchiveButton); // <--- ДОДАЄМО КНОПКУ В РЯД
        rowsInline.add(rowCancelButton); // <--- ДОДАЄМО РЯД ДО КЛАВІАТУРИ

        markupInline.setKeyboard(rowsInline);
        sendMessage.setReplyMarkup(markupInline);

        producerService.producerAnswer(sendMessage);
        log.info("Надіслано опції архівування (з кнопкою скасування) до чату {}", chatId);
    }

    private void sendDocumentFormatSelectionMessage(Long chatId) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(chatId.toString());
        sendMessage.setText("Оберіть цільовий формат для конвертації документа (.docx):");

        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

        List<InlineKeyboardButton> rowInline1 = new ArrayList<>();
        rowInline1.add(InlineKeyboardButton.builder().text("PDF").callbackData("format_select_doc_pdf").build());
        rowInline1.add(InlineKeyboardButton.builder().text("ODT").callbackData("format_select_doc_odt").build());
        // Можна додати інші формати, наприклад, конвертація в TXT або назад в DOCX (якщо потрібно перезберегти)
        // rowInline1.add(InlineKeyboardButton.builder().text("DOCX").callbackData("format_select_doc_docx").build());
        // rowInline1.add(InlineKeyboardButton.builder().text("TXT").callbackData("format_select_doc_txt").build());


        List<InlineKeyboardButton> rowCancel = new ArrayList<>();
        rowCancel.add(InlineKeyboardButton.builder().text("❌ Скасувати вибір").callbackData("cancel_format_selection").build());

        rowsInline.add(rowInline1);
        rowsInline.add(rowCancel);

        markupInline.setKeyboard(rowsInline);
        sendMessage.setReplyMarkup(markupInline);

        producerService.producerAnswer(sendMessage);
        log.info("Sent document format selection keyboard to chat_id: {}", chatId);
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

        if (ARCHIVING_FILES.equals(appUser.getState())) {
            if (message.getPhoto() != null && !message.getPhoto().isEmpty()) {
                PhotoSize photoSize = message.getPhoto().stream()
                        .max(Comparator.comparing(PhotoSize::getFileSize))
                        .orElse(null);

                if (photoSize != null) {
                    String fileId = photoSize.getFileId();
                    // Генеруємо ім'я файлу для фото, оскільки Telegram його не надає стандартно
                    String originalFileName = "photo_" + fileId + "_" + System.currentTimeMillis() + ".jpg";

                    ArchiveFileDetailDTO fileDetail = new ArchiveFileDetailDTO(fileId, originalFileName, "photo");

                    List<ArchiveFileDetailDTO> userArchiveFiles = archivingSessions.computeIfAbsent(appUser.getId(), k -> new ArrayList<>());
                    userArchiveFiles.add(fileDetail);
                    log.info("Додано фото '{}' (file_id: {}) до сесії архівування для користувача appUserId={}",
                            originalFileName, fileId, appUser.getId());

                    sendArchiveOptions(chatId, "Фото '" + originalFileName + "' отримано.");
                } else {
                    log.warn("Не вдалося отримати дані фото для архівування від користувача appUserId={}", appUser.getId());
                    sendAnswer("Помилка: не вдалося обробити фото. Спробуйте надіслати ще раз.", chatId);
                }
            } else {
                log.warn("Очікувалося фото для архівування від користувача appUserId={}, але його не знайдено в повідомленні.", appUser.getId());
                sendAnswer("Помилка: очікувалося фото, але його не знайдено. Спробуйте надіслати ще раз.", chatId);
            }
            return; // Важливо завершити обробку тут
        }

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
                    String outputMessage = "Фото завантажено! Посилання: " + link
                            + "\n\nДля виходу з режиму генерації посилань натисніть /cancel або відправте наступне фото.";
                    sendAnswer(outputMessage, chatId);
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
            sendAnswer("Очікувалося голосове повідомлення, але воно відсутнє.", chatId);
            return;
        }
        if ((BASIC_STATE.equals(appUser.getState()) || EMAIL_CONFIRMED_STATE.equals(appUser.getState())) && appUser.isActive()) {
            String permissionError = checkPermissionError(appUser); // Додаткова перевірка, хоча isActive вже перевірили
            if (permissionError != null) {
                sendAnswer(permissionError, chatId);
                return;
            }
            try {
                AppAudio audioEntity = fileService.processAudio(message); // Використовуємо новий метод
                if (audioEntity != null) {
                    String link = fileService.generateLink(audioEntity.getId(), LinkType.GET_AUDIO);
                    String outputMessage = "🎤 Голосове повідомлення '" + audioEntity.getFileName() + "' завантажено!\nПосилання: " + link
                            + "\n\nДля виходу з режиму генерації посилань натисніть /cancel або відправте наступний файл.";
                    sendAnswer(outputMessage, chatId);
                } else {
                    sendAnswer("Не вдалося обробити голосове повідомлення для генерації посилання.", chatId);
                }
            } catch (UploadFileException e) {
                log.error("Помилка UploadFileException при обробці голосового для посилання: {}", e.getMessage(), e);
                sendAnswer("Помилка при обробці голосового повідомлення: " + e.getMessage(), chatId);
            } catch (Exception e) {
                log.error("Загальна помилка при обробці голосового для посилання: {}", e.getMessage(), e);
                sendAnswer("Помилка при збереженні голосового повідомлення.", chatId);
            }
            return; // Завершуємо обробку, якщо згенерували посилання
        }
        if (ARCHIVING_FILES.equals(appUser.getState())) {
            if (telegramVoice != null) {
                String fileId = telegramVoice.getFileId();
                // Генеруємо ім'я файлу для голосового повідомлення
                // Telegram зазвичай надсилає голосові в контейнері OGG з кодеком OPUS.
                String originalFileName = "voice_" + fileId + "_" + System.currentTimeMillis() + ".ogg";

                ArchiveFileDetailDTO fileDetail = new ArchiveFileDetailDTO(fileId, originalFileName, "voice"); // Тип "voice" або "audio"

                List<ArchiveFileDetailDTO> userArchiveFiles = archivingSessions.computeIfAbsent(appUser.getId(), k -> new ArrayList<>());
                userArchiveFiles.add(fileDetail);
                log.info("Додано голосове повідомлення '{}' (file_id: {}) до сесії архівування для користувача appUserId={}",
                        originalFileName, fileId, appUser.getId());

                sendArchiveOptions(chatId, "Голосове повідомлення '" + originalFileName + "' отримано.");
            } else {
                // Цей блок спрацює, якщо telegramVoice == null, хоча основна перевірка є нижче.
                // Якщо ми потрапили сюди з ARCHIVING_FILES, але telegramVoice == null, що малоймовірно для цього методу.
                log.warn("Очікувалося голосове повідомлення для архівування від користувача appUserId={}, але воно відсутнє.", appUser.getId());
                sendAnswer("Помилка: очікувалося голосове повідомлення, але його не знайдено. Спробуйте надіслати ще раз.", chatId);
            }
            return; // Важливо завершити обробку тут
        }

        if (AWAITING_FILE_FOR_CONVERSION.equals(appUser.getState())) {
            if (!appUser.isActive()) {
                sendAnswer("Активуйте акаунт для конвертації.", chatId);
                return;
            }

            // Голосові зазвичай у форматі .ogg або подібному
            String originalFileName = "voice_" + appUser.getTelegramUserId() + "_" + System.currentTimeMillis() + ".ogg";
            String fileId = telegramVoice.getFileId();

            appUser.setPendingFileId(fileId);
            appUser.setPendingOriginalFileName(originalFileName);
            appUser.setPendingFileType("audio"); // Обробляємо як загальне аудіо
            appUser.setState(AWAITING_TARGET_FORMAT_SELECTION);
            appUserDAO.save(appUser);

            sendAudioFormatSelectionMessage(chatId); // Та сама клавіатура, що і для аудіофайлів
            log.info("Voice message received from user {}. Switched to AWAITING_TARGET_FORMAT_SELECTION. FileID: {}, FileName: {}",
                    appUser.getTelegramUserId(), fileId, originalFileName);
            return;
        } else {
            sendAnswer("Голосове повідомлення отримано, але бот не в режимі конвертації. Використайте /convert_file.", chatId);
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
        if ((BASIC_STATE.equals(appUser.getState()) || EMAIL_CONFIRMED_STATE.equals(appUser.getState())) && appUser.isActive()) {
            String permissionError = checkPermissionError(appUser);
            if (permissionError != null) {
                sendAnswer(permissionError, chatId);
                return;
            }
            try {
                // fileService.processVideo тепер поверне null, якщо відео завелике
                AppVideo videoEntity = fileService.processVideo(message);

                if (videoEntity != null) {
                    String link = fileService.generateLink(videoEntity.getId(), LinkType.GET_VIDEO);
                    String originalFileNameDisplay = videoEntity.getFileName() != null && !videoEntity.getFileName().isBlank()
                            ? videoEntity.getFileName()
                            : "Відеофайл";
                    String outputMessage = "🎬 Відеофайл '" + originalFileNameDisplay + "' завантажено!\nПосилання: " + link
                            + "\n\nДля виходу з режиму генерації посилань натисніть /cancel або відправте наступний файл.";
                    sendAnswer(outputMessage, chatId);
                } else {
                    // Перевіряємо розмір файлу, оскільки videoEntity може бути null через ліміт
                    if (telegramVideo.getFileSize() > 20 * 1024 * 1024) { // 20MB
                        sendAnswer("На жаль, відеофайл занадто великий (більше 20MB) для створення прямого посилання. Максимальний розмір: 20MB.", chatId);
                    } else {
                        sendAnswer("Не вдалося обробити відеофайл для генерації посилання.", chatId);
                    }
                }
            } catch (UploadFileException e) {
                log.error("Помилка UploadFileException при обробці відео для посилання: {}", e.getMessage(), e);
                sendAnswer("Помилка при обробці відеофайлу: " + e.getMessage(), chatId);
            } catch (Exception e) {
                log.error("Загальна помилка при обробці відео для посилання: {}", e.getMessage(), e);
                sendAnswer("Помилка при збереженні відеофайлу.", chatId);
            }
            return;
        }
        // ОБРОБКА ВІДЕО ДЛЯ АРХІВУВАННЯ
        if (ARCHIVING_FILES.equals(appUser.getState())) {
            if (telegramVideo != null) {
                String fileId = telegramVideo.getFileId();
                String originalFileName = telegramVideo.getFileName();

                // Якщо оригінальне ім'я файлу відсутнє, генеруємо його
                if (originalFileName == null || originalFileName.isEmpty()) {
                    String mimeType = telegramVideo.getMimeType() != null ? telegramVideo.getMimeType().toLowerCase() : "video/mp4";
                    String ext = "mp4"; // За замовчуванням
                    if (mimeType.contains("mp4")) ext = "mp4";
                    else if (mimeType.contains("quicktime")) ext = "mov";
                    else if (mimeType.contains("x-msvideo")) ext = "avi";
                    else if (mimeType.contains("x-matroska")) ext = "mkv";
                    else if (mimeType.contains("webm")) ext = "webm";
                    originalFileName = "video_" + fileId + "_" + System.currentTimeMillis() + "." + ext;
                    log.info("Згенеровано ім'я файлу для відео: {}", originalFileName);
                }

                ArchiveFileDetailDTO fileDetail = new ArchiveFileDetailDTO(fileId, originalFileName, "video");

                List<ArchiveFileDetailDTO> userArchiveFiles = archivingSessions.computeIfAbsent(appUser.getId(), k -> new ArrayList<>());
                userArchiveFiles.add(fileDetail);
                log.info("Додано відео '{}' (file_id: {}) до сесії архівування для користувача appUserId={}",
                        originalFileName, fileId, appUser.getId());

                sendArchiveOptions(chatId, "Відео '" + originalFileName + "' отримано.");
            } else {
                log.warn("Очікувалося відео для архівування від користувача appUserId={}, але воно відсутнє.", appUser.getId());
                sendAnswer("Помилка: очікувалося відео, але його не знайдено. Спробуйте надіслати ще раз.", chatId);
            }
            return; // Важливо завершити обробку тут
        }

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


    //TODO: dlya perevirky


    @Override
    @Transactional
    public void processAudioFileMessage(Update update) {
        saveRawData(update);
        var appUser = findOrSaveAppUser(update);
        if (appUser == null) return;

        var chatId = update.getMessage().getChatId();
        Message message = update.getMessage();
        org.telegram.telegrambots.meta.api.objects.Audio telegramAudio = message.getAudio();
        if ((BASIC_STATE.equals(appUser.getState()) || EMAIL_CONFIRMED_STATE.equals(appUser.getState())) && appUser.isActive()) {
            String permissionError = checkPermissionError(appUser);
            if (permissionError != null) {
                sendAnswer(permissionError, chatId);
                return;
            }
            try {
                AppAudio audioEntity = fileService.processAudio(message); // Використовуємо той самий метод processAudio
                if (audioEntity != null) {
                    String link = fileService.generateLink(audioEntity.getId(), LinkType.GET_AUDIO);
                    String originalFileNameDisplay = audioEntity.getFileName() != null && !audioEntity.getFileName().isBlank()
                            ? audioEntity.getFileName()
                            : "Аудіофайл";
                    String outputMessage = "🎧 Аудіофайл '" + originalFileNameDisplay + "' завантажено!\nПосилання: " + link
                            + "\n\nДля виходу з режиму генерації посилань натисніть /cancel або відправте наступний файл.";
                    sendAnswer(outputMessage, chatId);
                } else {
                    sendAnswer("Не вдалося обробити аудіофайл для генерації посилання.", chatId);
                }
            } catch (UploadFileException e) {
                log.error("Помилка UploadFileException при обробці аудіофайлу для посилання: {}", e.getMessage(), e);
                sendAnswer("Помилка при обробці аудіофайлу: " + e.getMessage(), chatId);
            } catch (Exception e) {
                log.error("Загальна помилка при обробці аудіофайлу для посилання: {}", e.getMessage(), e);
                sendAnswer("Помилка при збереженні аудіофайлу.", chatId);
            }
            return; // Завершуємо обробку, якщо згенерували посилання
        }
        if (ARCHIVING_FILES.equals(appUser.getState())) {
            if (telegramAudio != null) {
                String fileId = telegramAudio.getFileId();
                String originalFileName = telegramAudio.getFileName();

                // Якщо оригінальне ім'я файлу відсутнє або некоректне, генеруємо його
                if (originalFileName == null || originalFileName.isEmpty() || !originalFileName.contains(".")) {
                    String ext = "dat"; // За замовчуванням, якщо MIME-тип невідомий
                    if (telegramAudio.getMimeType() != null) {
                        String mimeType = telegramAudio.getMimeType().toLowerCase();
                        if (mimeType.contains("mpeg") || mimeType.contains("mp3")) ext = "mp3";
                        else if (mimeType.contains("ogg")) ext = "ogg";
                        else if (mimeType.contains("wav")) ext = "wav";
                        else if (mimeType.contains("aac")) ext = "aac";
                        else if (mimeType.contains("flac")) ext = "flac";
                        else if (mimeType.contains("mp4") || mimeType.contains("m4a")) ext = "m4a"; // Часто mp4 контейнер для aac
                    }
                    originalFileName = "audio_" + fileId + "_" + System.currentTimeMillis() + "." + ext;
                    log.info("Згенеровано ім'я файлу для аудіо: {}", originalFileName);
                }

                ArchiveFileDetailDTO fileDetail = new ArchiveFileDetailDTO(fileId, originalFileName, "audio");

                List<ArchiveFileDetailDTO> userArchiveFiles = archivingSessions.computeIfAbsent(appUser.getId(), k -> new ArrayList<>());
                userArchiveFiles.add(fileDetail);
                log.info("Додано аудіофайл '{}' (file_id: {}) до сесії архівування для користувача appUserId={}",
                        originalFileName, fileId, appUser.getId());

                sendArchiveOptions(chatId, "Аудіофайл '" + originalFileName + "' отримано.");
            } else {
                log.warn("Очікувався аудіофайл для архівування від користувача appUserId={}, але він відсутній.", appUser.getId());
                sendAnswer("Помилка: очікувався аудіофайл, але його не знайдено. Спробуйте надіслати ще раз.", chatId);
            }
            return; // Важливо завершити обробку тут
        }

        if (telegramAudio == null) {
            sendAnswer("Очікувався аудіофайл, але він відсутній.", chatId);
            return;
        }

        log.info("ENTERING processAudioFileMessage for user {}. File: {}, MIME: {}, Duration: {}",
                appUser.getTelegramUserId(), telegramAudio.getFileName(), telegramAudio.getMimeType(), telegramAudio.getDuration());

        if (AWAITING_FILE_FOR_CONVERSION.equals(appUser.getState())) {
            if (!appUser.isActive()) {
                sendAnswer("Будь ласка, активуйте свій акаунт для конвертації.", chatId);
                return;
            }

            String originalFileName = telegramAudio.getFileName();
            if (originalFileName == null || originalFileName.isEmpty() || !originalFileName.contains(".")) {
                String ext = "dat";
                if (telegramAudio.getMimeType() != null) {
                    if (telegramAudio.getMimeType().contains("mpeg")) ext = "mp3";
                    else if (telegramAudio.getMimeType().contains("ogg")) ext = "ogg";
                    else if (telegramAudio.getMimeType().contains("wav")) ext = "wav";
                    else if (telegramAudio.getMimeType().contains("aac")) ext = "aac";
                    else if (telegramAudio.getMimeType().contains("flac")) ext = "flac";
                    // Можна додати ще MIME типи, наприклад, audio/x-m4a для m4a
                }
                originalFileName = "audio_" + appUser.getTelegramUserId() + "_" + System.currentTimeMillis() + "." + ext;
                log.info("Generated filename for audio message: {}", originalFileName);
            }

            String fileId = telegramAudio.getFileId();

            appUser.setPendingFileId(fileId);
            appUser.setPendingOriginalFileName(originalFileName);
            appUser.setPendingFileType("audio"); // <--- Встановлюємо тип "audio"
            appUser.setState(AWAITING_TARGET_FORMAT_SELECTION);
            appUserDAO.save(appUser);

            sendAudioFormatSelectionMessage(chatId); // <--- Новий метод для вибору формату аудіо
            log.info("Audio file received from user {}. Switched to AWAITING_TARGET_FORMAT_SELECTION. FileID: {}, FileName: {}",
                    appUser.getTelegramUserId(), fileId, originalFileName);
            return;

        } else {
            sendAnswer("Аудіофайл '" + (telegramAudio.getFileName() != null ? telegramAudio.getFileName() : "невідомий") +
                    "' отримано, але бот не в режимі конвертації. Використайте /convert_file.", chatId);
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
                + "/create_archive - увімкнути режим архіватора\n"
                + "/generate_link - перейти в режим файлообмінника (вимкнути конвертацію)";
    }

    @Transactional
    protected String cancelProcess(AppUser appUser) {
        String previousStateInfo = "";
        // Отримуємо поточний стан користувача
        var currentState = appUser.getState(); // Використовуй appUser.getState()

        if (AWAITING_FILE_FOR_CONVERSION.equals(currentState)) {
            previousStateInfo = "Режим конвертації скасовано. ";
            // Можливо, тут також потрібно очищати pendingFileId, pendingOriginalFileName, pendingFileType
            // appUser.setPendingFileId(null);
            // appUser.setPendingOriginalFileName(null);
            // appUser.setPendingFileType(null);
        } else if (ARCHIVING_FILES.equals(currentState)) { // <--- ДОДАНО ЦЕЙ ELSE IF
            clearArchiveSession(appUser.getId()); // Викликаємо очищення сесії архівування
            previousStateInfo = "Сесію створення архіву скасовано. ";
            log.info("User {} (appUserId={}) cancelled archiving session.", appUser.getTelegramUserId(), appUser.getId());
        }
        // Інші стани, які можуть потребувати очищення, можна додати тут

        appUser.setState(BASIC_STATE); // Встановлюємо базовий стан
        appUserDAO.save(appUser);
        log.info("User {} (appUserId={}) cancelled current operation. State set to BASIC_STATE", appUser.getTelegramUserId(), appUser.getId());
        return previousStateInfo + "Ви повернулися в основний режим. Можете надсилати файли для обміну або використати команди: /convert_file, /create_archive ";
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
    @Override
    public void processCallbackQuery(Update update) {
        saveRawData(update); // Зберігаємо RawData, як ти робиш в інших processXxx методах

        if (update == null || !update.hasCallbackQuery() || update.getCallbackQuery().getData() == null) {
            log.warn("Отримано порожній або невалідний CallbackQuery.");
            return;
        }

        var callbackQuery = update.getCallbackQuery();
        String callbackData = callbackQuery.getData();
        Long chatId = callbackQuery.getMessage().getChatId(); // Отримуємо chatId з повідомлення, до якого прикріплена клавіатура

        // Отримуємо користувача. findOrSaveAppUser має вміти обробляти update з CallbackQuery
        var appUser = findOrSaveAppUser(update);
        if (appUser == null) {
            log.warn("Не вдалося знайти або створити користувача для CallbackQuery від chat_id: {}", chatId);
            // Можливо, відповісти користувачеві про помилку, якщо це доречно
            // producerService.producerAnswerCallbackQuery(callbackQuery.getId(), "Помилка: користувача не знайдено");
            return;
        }

        log.info("Отримано CallbackQuery від користувача appUserId={}, telegramUserId={}, дані: '{}'",
                appUser.getId(), appUser.getTelegramUserId(), callbackData);

        // Обробка callback-даних для архівування
        if ("ARCHIVE_CREATE_NOW".equals(callbackData)) {
            if (!ARCHIVING_FILES.equals(appUser.getState())) {
                log.warn("Користувач appUserId={} натиснув 'ARCHIVE_CREATE_NOW', але не перебуває у стані ARCHIVING_FILES. Поточний стан: {}", appUser.getId(), appUser.getState());
                sendAnswer("Ви не перебуваєте в режимі створення архіву. Щоб розпочати, надішліть команду /create_archive.", chatId);
                // Відповідаємо на callback, щоб прибрати "годинник" з кнопки
                producerService.producerAnswerCallbackQuery(callbackQuery.getId(), "Помилка стану");
                return;
            }

            List<ArchiveFileDetailDTO> filesToArchive = archivingSessions.get(appUser.getId());
            if (filesToArchive == null || filesToArchive.isEmpty()) {
                sendAnswer("Немає файлів для архівування. Спочатку надішліть файли.", chatId);
                // Очищаємо сесію (хоча вона і так порожня) і повертаємо в базовий стан
                clearArchiveSession(appUser.getId());
                appUser.setState(BASIC_STATE);
                appUserDAO.save(appUser);
                producerService.producerAnswerCallbackQuery(callbackQuery.getId(), "Файли не знайдено");
            } else {
                // Тут буде логіка створення архіву (Крок 3)
                log.info("Користувач appUserId={} натиснув 'Створити архів'. Кількість файлів: {}", appUser.getId(), filesToArchive.size());
                sendAnswer("Розпочинаю створення архіву з " + filesToArchive.size() + " файлів... (реалізація на наступному етапі)", chatId);
                producerService.producerAnswerCallbackQuery(callbackQuery.getId(), "Обробка..."); // Повідомлення для кнопки, що процес почався

                try {
                    // Крок 1: Створення архіву
                    byte[] archiveBytes = fileService.createZipArchiveFromTelegramFiles(filesToArchive);

                    if (archiveBytes == null || archiveBytes.length == 0) {
                        log.error("Згенерований архів порожній або сталася помилка при його створенні для користувача appUserId={}", appUser.getId());
                        sendAnswer("Не вдалося створити архів або він порожній. Спробуйте ще раз.", chatId);
                        // Не очищаємо сесію, щоб користувач міг спробувати знову або скасувати
                        return;
                    }

                    // Крок 2: Генерація імені файлу для архіву
                    String archiveFileName = "archive_" + appUser.getTelegramUserId() + "_" + System.currentTimeMillis() + ".zip";

                    // Крок 3: Збереження архіву як AppDocument
                    AppDocument savedArchive = fileService.saveGeneratedArchive(appUser, archiveBytes, archiveFileName);

                    if (savedArchive != null && savedArchive.getId() != null) { // Перевіряємо, що збереження в БД пройшло успішно
                        log.info("Архів '{}' (id={}) успішно збережено в БД для користувача appUserId={}.",
                                archiveFileName, savedArchive.getId(), appUser.getId());

                        // Крок 4: Надсилання архіву користувачеві, передаючи байти та ім'я файлу
                        DocumentToSendDTO archiveDto = DocumentToSendDTO.builder()
                                .chatId(chatId.toString())
                                .documentBytes(archiveBytes)    // <--- ВИПРАВЛЕНО: передаємо байти архіву
                                .fileName(archiveFileName)      // <--- ВИПРАВЛЕНО: передаємо ім'я файлу
                                .caption("Ваш архів '" + archiveFileName + "' готовий!\n\nВи в основному режимі, надішліть файл для генерації посилань або виконайте команду /help для перегляду команд!")
                                .build();
                        producerService.producerSendDocumentDTO(archiveDto); // Метод producerService має обробляти DTO з байтами
                        log.info("Запит на надсилання архіву '{}' (байти) для користувача appUserId={} відправлено до ProducerService.",
                                archiveFileName, appUser.getId());
                    }  else {
                        log.error("Не вдалося зберегти згенерований архів '{}' в БД для користувача appUserId={}", archiveFileName, appUser.getId());
                        sendAnswer("Не вдалося зберегти архів після створення. Будь ласка, повідомте адміністратора.", chatId);
                        // Сесію тут можна очистити, оскільки архів створено, але не збережено - це проблема на боці сервера.
                        // Або залишити, щоб користувач не втратив файли для наступної спроби, якщо проблема тимчасова.
                        // Поки що не будемо очищати сесію, щоб уникнути втрати файлів.
                        return;
                    }

                } catch (IOException e) {
                    log.error("Помилка IOException при створенні архіву для користувача appUserId={}: {}", appUser.getId(), e.getMessage(), e);
                    sendAnswer("Сталася помилка під час створення архіву. Спробуйте пізніше або зверніться до підтримки.", chatId);
                    // Не очищаємо сесію, щоб користувач міг спробувати знову
                    return;
                } catch (IllegalArgumentException e) { // Для помилок валідації в saveGeneratedArchive
                    log.error("Помилка IllegalArgumentException при збереженні архіву для appUserId={}: {}", appUser.getId(), e.getMessage(), e);
                    sendAnswer("Помилка даних при спробі зберегти архів. Будь ласка, повідомте адміністратора.", chatId);
                    return;
                } catch (UploadFileException e) { // Для помилок збереження, які кидає saveGeneratedArchive
                    log.error("Помилка UploadFileException при збереженні архіву для appUserId={}: {}", appUser.getId(), e.getMessage(), e);
                    sendAnswer("Не вдалося зберегти архів через помилку: " + e.getMessage() + ". Спробуйте пізніше.", chatId);
                    return;
                } catch (Exception e) { // Загальна помилка
                    log.error("Непередбачена помилка при створенні або збереженні архіву для appUserId={}: {}", appUser.getId(), e.getMessage(), e);
                    sendAnswer("Сталася непередбачена помилка. Спробуйте пізніше або зверніться до підтримки.", chatId);
                    // Не очищаємо сесію
                    return;
                } finally {
                }
                clearArchiveSession(appUser.getId());
                appUser.setState(BASIC_STATE);
                appUserDAO.save(appUser);
                log.info("Сесію архівування для appUserId={} очищено, стан встановлено на BASIC_STATE після операції створення архіву.", appUser.getId());

            }

        } else if ("ARCHIVE_ADD_MORE".equals(callbackData)) {
            if (!ARCHIVING_FILES.equals(appUser.getState())) {
                log.warn("Користувач appUserId={} натиснув 'ARCHIVE_ADD_MORE', але не перебуває у стані ARCHIVING_FILES. Поточний стан: {}", appUser.getId(), appUser.getState());
                sendAnswer("Ви не перебуваєте в режимі створення архіву. Щоб розпочати, надішліть команду /create_archive.", chatId);
                producerService.producerAnswerCallbackQuery(callbackQuery.getId(), "Помилка стану");
                return;
            }

            sendAnswer("Очікую наступний файл...", chatId);
            // Відповідаємо на callback, щоб прибрати "годинник"
            producerService.producerAnswerCallbackQuery(callbackQuery.getId(), "Надішліть ще файл");

        } else if ("ARCHIVE_CANCEL_SESSION".equals(callbackData)) {
        if (!ARCHIVING_FILES.equals(appUser.getState())) {
            log.warn("Користувач appUserId={} натиснув 'ARCHIVE_CANCEL_SESSION', але не перебуває у стані ARCHIVING_FILES. Поточний стан: {}", appUser.getId(), appUser.getState());
            // Можна нічого не відповідати або відповісти, що він не в режимі архівування
            producerService.producerAnswerCallbackQuery(callbackQuery.getId(), "Ви не в режимі архівування");
            return;
        }

        clearArchiveSession(appUser.getId()); // Очищаємо сесію
        appUser.setState(BASIC_STATE);      // Повертаємо в базовий стан
        appUserDAO.save(appUser);
        log.info("Користувач appUserId={} скасував сесію архівування через кнопку.", appUser.getId());

        sendAnswer("Створення архіву скасовано. Ви повернулися в основний режим.\n\nНадішліть файл для генерації посилання, або виконайте команду /help.", chatId);
        producerService.producerAnswerCallbackQuery(callbackQuery.getId(), "Скасовано"); // Відповідь на callback
        // ВСТАВ ДО ЦЬОГО МІСЦЯ

    } else if (callbackData.startsWith("format_select_") || "cancel_format_selection".equals(callbackData)) {
            // Якщо це callback для вибору формату, передаємо його існуючому обробнику
            log.debug("Перенаправлення CallbackQuery '{}' до processFormatSelectionCallback для користувача appUserId={}", callbackData, appUser.getId());
            processFormatSelectionCallback(update); // Викликаємо твій існуючий метод

        } else {
            log.warn("Отримано невідомий CallbackQuery: '{}' від користувача appUserId={}", callbackData, appUser.getId());
            // Можна відповісти на callback, щоб кнопка перестала "думати"
            producerService.producerAnswerCallbackQuery(callbackQuery.getId(), "Невідома команда");
        }
    }
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
            if (callbackQuery != null && callbackQuery.getId() != null) {
                producerService.producerAnswerCallbackQuery(callbackQuery.getId(), "Вибір скасовано");
            }
            return; // <--- ВАЖЛИВО
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

            }else if (callbackData.startsWith("format_select_audio_") && "audio".equals(pendingFileType)) { // <--- НОВА ГІЛКА ДЛЯ АУДІО
                // ОБРОБКА ВИБОРУ ФОРМАТУ ДЛЯ АУДІО
                String targetFormat = callbackData.substring("format_select_audio_".length());
                log.info("User selected AUDIO format '{}'. Pending file type: {}", targetFormat, pendingFileType);

                String fileIdForConversion = appUser.getPendingFileId();
                String originalFileNameForConversion = appUser.getPendingOriginalFileName();

                if (fileIdForConversion == null || originalFileNameForConversion == null) {
                    log.error("Pending AUDIO file ID or original name is null for user {}...", appUser.getTelegramUserId());
                    sendAnswer("Помилка: не можу знайти АУДІОФАЙЛ, який ви хотіли конвертувати...", chatId);
                    appUser.setPendingFileId(null);
                    appUser.setPendingOriginalFileName(null);
                    appUser.setPendingFileType(null);
                    appUser.setState(AWAITING_FILE_FOR_CONVERSION);
                    appUserDAO.save(appUser);
                    if (callbackQuery != null && callbackQuery.getId() != null) {
                        producerService.producerAnswerCallbackQuery(callbackQuery.getId(), "Помилка: аудіофайл не знайдено");
                    }
                    return;
                }

                byte[] fileData;
                try {
                    fileData = fileService.downloadFileAsByteArray(fileIdForConversion);
                    if (fileData == null || fileData.length == 0) throw new UploadFileException("Завантажений АУДІОФАЙЛ порожній.");
                    log.info("Successfully downloaded pending AUDIO for conversion: FileID='{}', OriginalName='{}', Size={}", fileIdForConversion, originalFileNameForConversion, fileData.length);
                } catch (Exception e) {
                    log.error("Failed to download pending AUDIO file_id {} for conversion: {}", fileIdForConversion, e.getMessage(), e);
                    sendAnswer("Не вдалося завантажити АУДІОФАЙЛ для конвертації...", chatId);
                    appUser.setPendingFileId(null);
                    appUser.setPendingOriginalFileName(null);
                    appUser.setPendingFileType(null);
                    appUser.setState(AWAITING_FILE_FOR_CONVERSION);
                    appUserDAO.save(appUser);
                    if (callbackQuery != null && callbackQuery.getId() != null) {
                        producerService.producerAnswerCallbackQuery(callbackQuery.getId(), "Помилка завантаження аудіо");
                    }
                    return;
                }

                final String finalOriginalAudioName = originalFileNameForConversion;
                ByteArrayResource audioFileResource = new ByteArrayResource(fileData) {
                    @Override public String getFilename() { return finalOriginalAudioName; }
                };

                String audioConverterApiEndpoint = "/api/audio/convert"; // Твій ендпоінт для аудіо
                boolean audioConversionSuccess = false;

                sendAnswer("Розпочинаю конвертацію АУДІО '" + originalFileNameForConversion + "' у формат " + targetFormat.toUpperCase() + "...", chatId);

                try {
                    // Переконайся, що convertAudioFile в ConverterClientServiceImpl
                    // та AudioConverterController в converter-service приймають targetFormat
                    ResponseEntity<byte[]> response = converterClientService.convertAudioFile(audioFileResource, originalFileNameForConversion, targetFormat, audioConverterApiEndpoint);

                    if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                        byte[] convertedAudioData = response.getBody();
                        String baseName = originalFileNameForConversion.contains(".") ?
                                originalFileNameForConversion.substring(0, originalFileNameForConversion.lastIndexOf('.')) :
                                originalFileNameForConversion;
                        String convertedAudioFileName = "converted_" + baseName + "." + targetFormat;

                        // Надсилаємо як документ
                        producerService.producerSendDocumentDTO(DocumentToSendDTO.builder()
                                .chatId(chatId.toString())
                                .documentBytes(convertedAudioData)
                                .fileName(convertedAudioFileName)
                                .caption("Сконвертоване аудіо: " + originalFileNameForConversion + " -> " + targetFormat.toUpperCase())
                                .build());
                        audioConversionSuccess = true;
                        log.info("Successfully converted and sent AUDIO as document '{}' (original: '{}') to format '{}' for user {}",
                                convertedAudioFileName, originalFileNameForConversion, targetFormat, appUser.getTelegramUserId());
                    } else {
                        log.error("AUDIO conversion failed for '{}' to {}. Status: {}.",
                                originalFileNameForConversion, targetFormat, response.getStatusCode());
                        sendAnswer("Помилка конвертації АУДІО в " + targetFormat.toUpperCase() + ". Статус: " + response.getStatusCode(), chatId);
                    }
                } catch (Exception e) {
                    log.error("Critical exception during AUDIO conversion for file {}: {}", originalFileNameForConversion, e.getMessage(), e);
                    sendAnswer("Критична помилка сервісу конвертації для вашого АУДІО.", chatId);
                } finally {
                    appUser.setPendingFileId(null);
                    appUser.setPendingOriginalFileName(null);
                    appUser.setPendingFileType(null); // Очищаємо тип

                    String callbackResponseMessage;
                    if (audioConversionSuccess) {
                        sendPostConversionMessage(chatId);
                        callbackResponseMessage = "Аудіо конвертовано в " + targetFormat.toUpperCase() + "!";
                    } else {
                        sendAnswer("Не вдалося сконвертувати АУДІО. Спробуйте ще раз.", chatId);
                        callbackResponseMessage = "Помилка конвертації аудіо";
                    }
                    appUser.setState(AWAITING_FILE_FOR_CONVERSION);
                    appUserDAO.save(appUser);
                    if (callbackQuery != null && callbackQuery.getId() != null) {
                        producerService.producerAnswerCallbackQuery(callbackQuery.getId(), callbackResponseMessage);
                    }
                }
            }  else if (callbackData.startsWith("format_select_") && "photo".equals(pendingFileType)) {
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

            } else if (callbackData.startsWith("format_select_doc_") && "document".equals(pendingFileType)) {
                String targetFormat = callbackData.substring("format_select_doc_".length());
                log.info("User {} selected DOCUMENT format '{}'. Pending file type: {}", appUser.getTelegramUserId(), targetFormat, pendingFileType);

                String fileIdForConversion = appUser.getPendingFileId();
                String originalFileNameForConversion = appUser.getPendingOriginalFileName();

                if (fileIdForConversion == null || originalFileNameForConversion == null) {
                    log.error("Pending DOCUMENT file ID or original name is null for user {}...", appUser.getTelegramUserId());
                    sendAnswer("Помилка: не можу знайти документ, який ви хотіли конвертувати. Будь ласка, надішліть його знову.", chatId);
                    appUser.setPendingFileId(null);
                    appUser.setPendingOriginalFileName(null);
                    appUser.setPendingFileType(null);
                    appUser.setState(AWAITING_FILE_FOR_CONVERSION);
                    appUserDAO.save(appUser);
                    if (callbackQuery != null && callbackQuery.getId() != null) {
                        producerService.producerAnswerCallbackQuery(callbackQuery.getId(), "Помилка: документ не знайдено");
                    }
                    return;
                }

                byte[] fileData;
                try {
                    // Завантажуємо файл тут, перед конвертацією
                    fileData = fileService.downloadFileAsByteArray(fileIdForConversion);
                    if (fileData == null || fileData.length == 0) throw new UploadFileException("Завантажений ДОКУМЕНТ файл порожній.");
                    log.info("Successfully downloaded pending DOCUMENT for conversion: FileID='{}', OriginalName='{}', Size={}", fileIdForConversion, originalFileNameForConversion, fileData.length);
                } catch (Exception e) {
                    log.error("Failed to download pending DOCUMENT file_id {} for conversion: {}", fileIdForConversion, e.getMessage(), e);
                    sendAnswer("Не вдалося завантажити документ для конвертації. Спробуйте надіслати його знову.", chatId);
                    appUser.setPendingFileId(null);
                    appUser.setPendingOriginalFileName(null);
                    appUser.setPendingFileType(null);
                    appUser.setState(AWAITING_FILE_FOR_CONVERSION);
                    appUserDAO.save(appUser);
                    if (callbackQuery != null && callbackQuery.getId() != null) {
                        producerService.producerAnswerCallbackQuery(callbackQuery.getId(), "Помилка завантаження документа");
                    }
                    return;
                }

                final String finalOriginalDocName = originalFileNameForConversion;
                ByteArrayResource docFileResource = new ByteArrayResource(fileData) {
                    @Override public String getFilename() { return finalOriginalDocName; }
                };

                String docConverterApiEndpoint = "/api/document/convert"; // Використовуємо існуючий ендпоінт
                boolean docConversionSuccess = false;
                String callbackResponseMessage = "Помилка конвертації документа"; // За замовчуванням

                sendAnswer("Файл '" + originalFileNameForConversion + "' отримано. Розпочинаю конвертацію у формат " + targetFormat.toUpperCase() + "...", chatId);

                try {
                    ResponseEntity<byte[]> response = converterClientService.convertFile(docFileResource, originalFileNameForConversion, targetFormat, docConverterApiEndpoint);

                    if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                        byte[] convertedDocData = response.getBody();
                        String baseName = originalFileNameForConversion.contains(".") ?
                                originalFileNameForConversion.substring(0, originalFileNameForConversion.lastIndexOf('.')) :
                                originalFileNameForConversion;
                        String convertedDocFileName = "converted_" + baseName + "." + targetFormat;

                        producerService.producerSendDocumentDTO(DocumentToSendDTO.builder()
                                .chatId(chatId.toString())
                                .documentBytes(convertedDocData)
                                .fileName(convertedDocFileName)
                                .caption("Сконвертований документ: " + originalFileNameForConversion + " -> " + targetFormat.toUpperCase())
                                .build());
                        docConversionSuccess = true;
                        callbackResponseMessage = "Документ конвертовано в " + targetFormat.toUpperCase() + "!";
                        log.info("Successfully converted and sent DOCUMENT '{}' (original: '{}') to format '{}' for user {}",
                                convertedDocFileName, originalFileNameForConversion, targetFormat, appUser.getTelegramUserId());
                    } else {
                        log.error("DOCUMENT conversion failed for '{}' to {}. Status: {}. Response body present: {}",
                                originalFileNameForConversion, targetFormat,
                                response.getStatusCode(), response.getBody() != null);
                        sendAnswer("Помилка конвертації документа в " + targetFormat.toUpperCase() + ". Статус: " + response.getStatusCode(), chatId);
                        callbackResponseMessage = "Помилка конвертації: " + response.getStatusCode();
                    }
                } catch (Exception e) {
                    log.error("Critical exception during DOCUMENT conversion for file {}: {}", originalFileNameForConversion, e.getMessage(), e);
                    sendAnswer("Критична помилка сервісу конвертації для вашого документа.", chatId);
                    callbackResponseMessage = "Критична помилка сервісу";
                } finally {
                    appUser.setPendingFileId(null);
                    appUser.setPendingOriginalFileName(null);
                    appUser.setPendingFileType(null);
                    appUser.setState(AWAITING_FILE_FOR_CONVERSION); // Повертаємо до очікування нового файлу
                    appUserDAO.save(appUser);

                    if (docConversionSuccess) {
                        sendPostConversionMessage(chatId); // Повідомлення про успішну конвертацію та що робити далі
                    }
                    // Відповідаємо на callback query в будь-якому випадку
                    if (callbackQuery != null && callbackQuery.getId() != null) {
                        producerService.producerAnswerCallbackQuery(callbackQuery.getId(), callbackResponseMessage);
                    }
                }
                // КІНЕЦЬ НОВОГО БЛОКУ ДЛЯ ДОКУМЕНТІВ else {
                // Невідомий callbackData або невідповідність pendingFileType
                log.warn("Mismatch or unknown callback_data: '{}' with pendingFileType: '{}' for user {} in state {}",
                        callbackData, pendingFileType, appUser.getTelegramUserId(), appUser.getState());
//                sendAnswer("Сталася незрозуміла помилка з вибором формату. Спробуйте знову.", chatId);
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
            }
        }
    }
    private void sendAudioFormatSelectionMessage(Long chatId) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(chatId.toString());
        sendMessage.setText("Оберіть цільовий формат для конвертації АУДІО:");

        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

        // Приклади форматів для аудіо
        List<InlineKeyboardButton> rowInline1 = new ArrayList<>();
        rowInline1.add(InlineKeyboardButton.builder().text("MP3").callbackData("format_select_audio_mp3").build());
        rowInline1.add(InlineKeyboardButton.builder().text("WAV").callbackData("format_select_audio_wav").build());

        List<InlineKeyboardButton> rowInline2 = new ArrayList<>();
        rowInline2.add(InlineKeyboardButton.builder().text("OGG (Vorbis)").callbackData("format_select_audio_ogg").build());
        rowInline2.add(InlineKeyboardButton.builder().text("FLAC").callbackData("format_select_audio_flac").build());

        List<InlineKeyboardButton> rowCancel = new ArrayList<>();
        rowCancel.add(InlineKeyboardButton.builder().text("❌ Скасувати вибір").callbackData("cancel_format_selection").build());

        // Можна додати M4A (AAC) або інші
        // List<InlineKeyboardButton> rowInline3 = new ArrayList<>();
        // rowInline3.add(InlineKeyboardButton.builder().text("M4A (AAC)").callbackData("format_select_audio_m4a").build());

        rowsInline.add(rowInline1);
        rowsInline.add(rowInline2);
        rowsInline.add(rowCancel);

        // if (!rowInline3.isEmpty()) rowsInline.add(rowInline3);

        markupInline.setKeyboard(rowsInline);
        sendMessage.setReplyMarkup(markupInline);

        producerService.producerAnswer(sendMessage);
        log.info("Sent audio format selection keyboard to chat_id: {}", chatId);
    }

}