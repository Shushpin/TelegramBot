package lnu.study.service.impl;

import lnu.study.dao.AppUserDAO;
import lnu.study.dao.RawDataDAO;
import lnu.study.entity.AppDocument;
import lnu.study.entity.AppUser;
import lnu.study.entity.RawData;
import lnu.study.entity.enums.UserState; // Додано явний імпорт, якщо раптом був відсутній
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
import static lnu.study.service.enums.ServiceCommand.*; // Імпортує всі константи з ServiceCommand


@Log4j2
@Service
public class MainServiceImpl implements MainService {
    private final RawDataDAO rawDataDAO;
    private final ProducerService producerService;
    private final AppUserDAO appUserDAO;
    private final FileService fileService;

    // Текст повідомлення винесено в константу для зручності
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
        var userState = appUser.getState();
        var text = update.getMessage().getText();
        var output = "";

        // Обробка сервісних команд
        var serviceCommand = ServiceCommand.fromValue(text);
        if (CANCEL.equals(serviceCommand)) {
            output = cancelProcess(appUser);
        } else if (BASIC_STATE.equals(userState)) {
            output = processServiceCommand(appUser, text);
        } else if (WAIT_FOR_EMAIL_STATE.equals(userState)) {
            //TODO добавить обработку емейла
            log.info("TODO: Email processing needed for user: " + appUser.getId());
            // Потрібно додати логіку обробки email або надіслати відповідь користувачу
            output = "Обробка email ще не реалізована.";
        } else {
            log.error("Unknown user state: " + userState);
            output = "Невідома помилка! Введіть /cancel і спробуйте знову!";
        }

        var chatId = update.getMessage().getChatId();
        // Надсилаємо фінальну відповідь (результат команди або помилку стану)
        sendAnswer(output, chatId);
    }

    @Override
    public void processDocMessage(Update update) {
        saveRawData(update);
        var appUser = findOrSaveAppUser(update);
        var chatId = update.getMessage().getChatId();

        // 1. Надсилаємо повідомлення "Обробляється..."
        sendAnswer(FILE_RECEIVED_MESSAGE, chatId);

        // 2. Перевіряємо дозвіл
        String permissionError = checkPermissionError(appUser);
        if (permissionError != null) {
            // 3а. Якщо дозволу немає, надсилаємо помилку і виходимо
            sendAnswer(permissionError, chatId);
            return;
        }

        // 3б. Якщо дозвіл є, обробляємо документ
        try {
            AppDocument doc = fileService.processDoc(update.getMessage());
            // TODO Добавить генерацию реальной ссылки для скачивания документа
            var answer = "Документ успішно завантажено! "
                    + "Посилання для скачування: http://test.ru/get-doc/" + doc.getId(); // Приклад використання ID
            sendAnswer(answer, chatId);
        } catch (UploadFileException ex) {
            log.error("UploadFileException occurred: ", ex); // Краще логувати сам виняток
            String error = "На жаль, завантаження файлу не вдалося. Спробуйте пізніше.";
            sendAnswer(error, chatId);
        } catch (Exception e) { // Додано обробку інших можливих винятків
            log.error("Exception occurred during doc processing: ", e);
            String error = "Сталася непередбачена помилка під час обробки файлу.";
            sendAnswer(error, chatId);
        }
    }

    @Override
    public void processPhotoMessage(Update update) {
        saveRawData(update);
        var appUser = findOrSaveAppUser(update);
        var chatId = update.getMessage().getChatId();

        // 1. Надсилаємо повідомлення "Обробляється..."
        sendAnswer(FILE_RECEIVED_MESSAGE, chatId); // Використовуємо те саме повідомлення

        // 2. Перевіряємо дозвіл
        String permissionError = checkPermissionError(appUser);
        if (permissionError != null) {
            // 3а. Якщо дозволу немає, надсилаємо помилку і виходимо
            sendAnswer(permissionError, chatId);
            return;
        }

        // 3б. Якщо дозвіл є, обробляємо фото
        // TODO: Додати логіку збереження фото та отримання посилання/ID
        try {
            // Тут має бути виклик сервісу для обробки фото, аналогічно до fileService.processDoc
            // fileService.processPhoto(update.getMessage());
            log.info("TODO: Photo processing logic to be implemented for update: " + update.getUpdateId());
            var answer = "Фото успішно завантажено! "
                    + "Посилання для скачування: http://test.ru/get-photo/777"; // Заглушка
            sendAnswer(answer, chatId);
        } catch (Exception e) { // Обробка можливих винятків при роботі з фото
            log.error("Exception occurred during photo processing: ", e);
            String error = "Сталася непередбачена помилка під час обробки фото.";
            sendAnswer(error, chatId);
        }
    }

    /**
     * Перевіряє, чи дозволено користувачу надсилати контент.
     * Повертає рядок з текстом помилки, якщо не дозволено, або null, якщо дозволено.
     * НЕ надсилає повідомлення самостійно.
     *
     * @param appUser Користувач для перевірки.
     * @return Текст помилки або null.
     */
    private String checkPermissionError(AppUser appUser) {
        var userState = appUser.getState();
        if (!appUser.isActive()) {
            return "Зареєструйтесь або активуйте "
                    + "свою обліковий запис для завантаження контенту.";
        } else if (!BASIC_STATE.equals(userState)) {
            return "Скасуйте поточну команду за допомогою /cancel для надсилання файлів.";
        }
        return null; // Все гаразд, дозвіл є
    }


    // --- Решта методів залишаються без змін ---

    private void sendAnswer(String output, Long chatId) {
        if (output == null || output.isEmpty()) {
            // Не надсилаємо порожні повідомлення
            log.warn("Attempted to send null or empty message to chat ID: " + chatId);
            return;
        }
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(chatId.toString()); // Краще використовувати toString() для chatId
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
        User telegramUser = update.getMessage().getFrom();
        // Додамо перевірку на null для telegramUser на випадок дивних апдейтів
        if (telegramUser == null) {
            log.error("Update received without user info: {}", update.getUpdateId());
            // Потрібно вирішити, що робити в цьому випадку. Кидати виняток? Повертати null?
            // Поки що повернемо null, але це може викликати NPE далі.
            return null; // Або кинути виняток
        }

        AppUser persistentAppUser = appUserDAO.findAppUserByTelegramUserId(telegramUser.getId());
        if (persistentAppUser == null) {
            AppUser transientAppUser = AppUser.builder()
                    .telegramUserId(telegramUser.getId())
                    .userName(telegramUser.getUserName()) // Змінено на userName (якщо поле в AppUser так називається)
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
        // Додамо перевірку, щоб не зберігати "порожні" Update
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