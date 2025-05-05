package lnu.study.service.impl;

import lnu.study.dao.AppUserDAO;
import lnu.study.dao.RawDataDAO;
import lnu.study.entity.AppDocument;
import lnu.study.entity.AppPhoto; // Потрібен імпорт AppPhoto
import lnu.study.entity.AppUser;
import lnu.study.entity.RawData;
import lnu.study.entity.enums.UserState;
import lnu.study.exceptions.UploadFileException;
import lnu.study.service.FileService;
import lnu.study.service.MainService;
import lnu.study.service.ProducerService;
import lnu.study.service.enums.ServiceCommand;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;

import static lnu.study.entity.enums.UserState.BASIC_STATE;
import static lnu.study.entity.enums.UserState.WAIT_FOR_EMAIL_STATE;
import static lnu.study.service.enums.ServiceCommand.*;


@Log4j2
@Service
public class MainServiceImpl implements MainService {
    private final RawDataDAO rawDataDAO;
    private final ProducerService producerService;
    private final AppUserDAO appUserDAO;
    private final FileService fileService;

    private static final String FILE_RECEIVED_MESSAGE = "Файл отримано! Обробляється...";

    public MainServiceImpl(RawDataDAO rawDataDAO,
                           ProducerService producerService,
                           AppUserDAO appUserDAO,
                           FileService fileService) {
        this.rawDataDAO = rawDataDAO;
        this.producerService = producerService;
        this.appUserDAO = appUserDAO;
        this.fileService = fileService;
    }

    @Override
    public void processTextMessage(Update update) {
        saveRawData(update);
        var appUser = findOrSaveAppUser(update);
        // Додано перевірку appUser після findOrSaveAppUser
        if (appUser == null) {
            log.warn("Cannot process text message for update {} as AppUser is null.", update.getUpdateId());
            return;
        }
        var userState = appUser.getState();
        var text = update.getMessage().getText();
        var output = "";

        var serviceCommand = ServiceCommand.fromValue(text);
        if (CANCEL.equals(serviceCommand)) {
            output = cancelProcess(appUser);
        } else if (BASIC_STATE.equals(userState)) {
            output = processServiceCommand(appUser, text);
        } else if (WAIT_FOR_EMAIL_STATE.equals(userState)) {
            //TODO добавить обработку емейла
            log.info("TODO: Email processing needed for user: " + appUser.getId());
            output = "Обробка email ще не реалізована.";
        } else {
            log.error("Unknown user state: " + userState);
            output = "Невідома помилка! Введіть /cancel і спробуйте знову!";
        }

        var chatId = update.getMessage().getChatId();
        sendAnswer(output, chatId);
    }

    @Override
    public void processDocMessage(Update update) {
        saveRawData(update);
        var appUser = findOrSaveAppUser(update);
        // Додано перевірку appUser після findOrSaveAppUser
        if (appUser == null) {
            log.warn("Cannot process doc message for update {} as AppUser is null.", update.getUpdateId());
            return;
        }
        var chatId = update.getMessage().getChatId();

        sendAnswer(FILE_RECEIVED_MESSAGE, chatId);

        String permissionError = checkPermissionError(appUser);
        if (permissionError != null) {
            sendAnswer(permissionError, chatId);
            return;
        }

        try {
            AppDocument doc = fileService.processDoc(update.getMessage());
            if (doc != null) {
                // TODO Добавить генерацию реальной ссылки для скачивания документа
                var answer = "Документ успішно завантажено! "
                        + "Посилання для скачування: http://test.ru/get-doc/" + doc.getId();
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

    @Override
    public void processPhotoMessage(Update update) {
        saveRawData(update);
        var appUser = findOrSaveAppUser(update);
        // Додано перевірку appUser після findOrSaveAppUser
        if (appUser == null) {
            log.warn("Cannot process photo message for update {} as AppUser is null.", update.getUpdateId());
            return;
        }
        var chatId = update.getMessage().getChatId();

        sendAnswer(FILE_RECEIVED_MESSAGE, chatId);

        String permissionError = checkPermissionError(appUser);
        if (permissionError != null) {
            sendAnswer(permissionError, chatId);
            return;
        }

        // --- ІНТЕГРОВАНО ТА ОНОВЛЕНО БЛОК ДЛЯ ФОТО ---
        try {
            // Викликаємо сервіс для обробки фото
            AppPhoto photo = fileService.processPhoto(update.getMessage());
            if (photo != null) {
                // TODO: Додати генерацию реальної посилання для скачування фото
                var answer = "Фото успішно завантажено! "
                        + "Посилання для скачування: http://test.ru/get-photo/" + photo.getId(); // Використовуємо ID фото
                sendAnswer(answer, chatId);
            } else {
                // Обробка випадку, коли сервіс повернув null, але не кинув UploadFileException
                log.error("Photo processing returned null for update: {}", update.getUpdateId());
                sendAnswer("Не вдалося обробити фото. Спробуйте пізніше.", chatId);
            }
        } catch (UploadFileException ex) { // Обробка специфічної помилки завантаження
            log.error("UploadFileException occurred during photo processing: ", ex);
            // Повідомлення про помилку стандартизовано українською
            String error = "На жаль, завантаження фото не вдалося. Спробуйте пізніше.";
            sendAnswer(error, chatId);
        } catch (Exception e) { // Обробка інших можливих помилок
            log.error("Exception occurred during photo processing: ", e);
            String error = "Сталася непередбачена помилка під час обробки фото.";
            sendAnswer(error, chatId);
        }
        // --- КІНЕЦЬ ІНТЕГРОВАНОГО БЛОКУ ---
    }


    private String checkPermissionError(AppUser appUser) {
        // Додано перевірку, що appUser не null перед доступом до його полів/методів
        if (appUser == null) {
            // Цього не мало б статися, якщо findOrSaveAppUser обробив null, але про всяк випадок
            return "Помилка: не вдалося визначити користувача.";
        }
        var userState = appUser.getState();
        if (!appUser.isActive()) {
            return "Зареєструйтесь або активуйте "
                    + "свою обліковий запис для завантаження контенту.";
        } else if (!BASIC_STATE.equals(userState)) {
            return "Скасуйте поточну команду за допомогою /cancel для надсилання файлів.";
        }
        return null; // Все гаразд, дозвіл є
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
        if (REGISTRATION.equals(serviceCommand)) {
            //TODO додати реєстрацію
            log.info("TODO: Registration command received from user: " + appUser.getId());
            return "Реєстрація тимчасово недоступна.";
        } else if (HELP.equals(serviceCommand)) {
            return help();
        } else if (START.equals(serviceCommand)) {
            return "Вітаю! Щоб переглянути список доступних команд, введіть /help";
        } else {
            return "Невідома команда! Щоб переглянути список доступних команд, введіть /help";
        }
    }

    private String help() {
        return "Список доступних команд:\n"
                + "/cancel - скасування виконання поточної команди;\n"
                + "/registration - реєстрація користувача.";
    }

    private String cancelProcess(AppUser appUser) {
        appUser.setState(BASIC_STATE);
        appUserDAO.save(appUser);
        return "Команду скасовано!";
    }

    private AppUser findOrSaveAppUser(Update update) {
        // Додано перевірку message на null
        if (update == null || update.getMessage() == null) {
            log.error("Update or message is null in findOrSaveAppUser");
            return null;
        }
        User telegramUser = update.getMessage().getFrom();
        if (telegramUser == null) {
            log.error("Update received without user info: {}", update.getUpdateId());
            return null;
        }

        AppUser persistentAppUser = appUserDAO.findAppUserByTelegramUserId(telegramUser.getId());
        if (persistentAppUser == null) {
            AppUser transientAppUser = AppUser.builder()
                    .telegramUserId(telegramUser.getId())
                    .userName(telegramUser.getUserName())
                    .firstName(telegramUser.getFirstName())
                    .lastName(telegramUser.getLastName())
                    //TODO змінити значення за замовчуванням після додавання реєстрації
                    .isActive(true)
                    .state(BASIC_STATE)
                    .build();
            return appUserDAO.save(transientAppUser);
        }
        return persistentAppUser;
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