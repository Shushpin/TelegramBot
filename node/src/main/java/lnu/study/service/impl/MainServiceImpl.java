package lnu.study.service.impl;

// Переконайтеся, що всі потрібні імпорти є
import lnu.study.dao.AppUserDAO;
import lnu.study.dao.RawDataDAO;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional; // Додамо, якщо метод checkPermissionError буде змінювати дані (хоча він не повинен)
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;

import java.util.Optional;

import static lnu.study.entity.enums.UserState.BASIC_STATE;
import static lnu.study.entity.enums.UserState.WAIT_FOR_EMAIL_STATE;
// Переконайтеся, що всі команди імпортовані
import static lnu.study.service.enums.ServiceCommand.*;

@Log4j2
@Service
public class MainServiceImpl implements MainService {
    private final RawDataDAO rawDataDAO;
    private final ProducerService producerService;
    private final AppUserDAO appUserDAO; // Переконайтеся, що DAO інжектовано
    private final FileService fileService;
    private final AppUserService appUserService;

    private static final String FILE_RECEIVED_MESSAGE = "Файл отримано! Обробляється...";

    public MainServiceImpl(RawDataDAO rawDataDAO,
                           ProducerService producerService,
                           AppUserDAO appUserDAO,
                           FileService fileService, AppUserService appUserService) {
        this.rawDataDAO = rawDataDAO;
        this.producerService = producerService;
        this.appUserDAO = appUserDAO; // Важливо, щоб DAO було тут
        this.fileService = fileService;
        this.appUserService = appUserService;
    }

    // processTextMessage залишається без змін (той, що ми виправили раніше)
    @Override
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

        ServiceCommand serviceCommand = ServiceCommand.fromValue(text); // Розпізнаємо команду на початку

        // 1. Обробляємо команди, які мають пріоритет або діють незалежно від стану
        if (CANCEL.equals(serviceCommand)) {
            output = cancelProcess(appUser);
        } else if (RESEND_EMAIL.equals(serviceCommand)) { // <<<=== ОБРОБКА /resend_email
            log.info("Processing /resend_email command for user {}", appUser.getTelegramUserId());
            output = appUserService.resendActivationEmail(appUser); // Викликаємо новий метод сервісу
        }
        // 2. Обробляємо інші випадки на основі стану користувача
        else if (WAIT_FOR_EMAIL_STATE.equals(userState)) {
            // Якщо стан - очікування email, перевіряємо, чи надісланий текст НЕ є командою
            if (serviceCommand == null) {
                // Це не команда, отже, вважаємо це email'ом
                log.info("User {} is in WAIT_FOR_EMAIL_STATE, processing text '{}' as email.", appUser.getTelegramUserId(), text);
                output = appUserService.setEmail(appUser, text);
            } else {
                // Користувач надіслав іншу команду (/start, /help, /registration) замість email
                log.warn("User {} sent command '{}' while in WAIT_FOR_EMAIL_STATE.", appUser.getTelegramUserId(), text);
                // Обробляємо стандартні команди, якщо вони не є "небезпечними"
                if (START.equals(serviceCommand) || HELP.equals(serviceCommand)) {
                    output = processServiceCommand(appUser, text); // Дозволяємо /start, /help
                } else if (REGISTRATION.equals(serviceCommand)) {
                    // Повторна реєстрація в цьому стані може бути небажаною
                    output = "Ви вже в процесі реєстрації. Будь ласка, надішліть свій email або введіть /cancel для скасування.";
                }
                else {
                    // Якщо це якась інша, непередбачена команда
                    output = "Ви перебуваєте в процесі введення email. Будь ласка, надішліть свій email або введіть /cancel для скасування.";
                }
            }
        } else if (BASIC_STATE.equals(userState)) {
            // У базовому стані обробляємо стандартні сервісні команди
            output = processServiceCommand(appUser, text);
        } else {
            log.warn("User {} is in an unhandled state: {}. Processing standard commands.", appUser.getTelegramUserId(), userState);
            output = processServiceCommand(appUser, text);
        }

        log.info("Final output for user_id: {}: '{}'", appUser.getTelegramUserId(), output);
        sendAnswer(output, update.getMessage().getChatId());
    }


    @Override
    public void processDocMessage(Update update) {
        saveRawData(update);
        var appUser = findOrSaveAppUser(update); // Початкове зчитування/створення
        if (appUser == null) {
            log.warn("Cannot process doc message for update {} as AppUser is null.", update.getUpdateId());
            return;
        }
        var chatId = update.getMessage().getChatId();
        var telegramUserId = appUser.getTelegramUserId(); // Отримуємо ID

        // Перевіряємо дозвіл, передаючи ID, щоб змусити перечитати дані з БД всередині checkPermissionError
        String permissionError = checkPermissionError(telegramUserId); // <<< ЗМІНА ТУТ
        if (permissionError != null) {
            sendAnswer(permissionError, chatId); // Відправляємо помилку і виходимо
            return;
        }

        // Якщо дозвіл є, тільки тоді відправляємо повідомлення про отримання
        sendAnswer(FILE_RECEIVED_MESSAGE, chatId);

        // Продовжуємо обробку документа...
        try {
            AppDocument doc = fileService.processDoc(update.getMessage());
            if (doc != null) {
                String link = fileService.generateLink(doc.getId(), LinkType.GET_DOC);
                var answer = "Документ успішно завантажено! "
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

    @Override
    public void processPhotoMessage(Update update) {
        saveRawData(update);
        var appUser = findOrSaveAppUser(update); // Початкове зчитування/створення
        if (appUser == null) {
            log.warn("Cannot process photo message for update {} as AppUser is null.", update.getUpdateId());
            return;
        }
        var chatId = update.getMessage().getChatId();
        var telegramUserId = appUser.getTelegramUserId(); // Отримуємо ID

        // Перевіряємо дозвіл, передаючи ID
        String permissionError = checkPermissionError(telegramUserId); // <<< ЗМІНА ТУТ
        if (permissionError != null) {
            sendAnswer(permissionError, chatId);
            return;
        }

        // Якщо дозвіл є, тільки тоді відправляємо повідомлення про отримання
        sendAnswer(FILE_RECEIVED_MESSAGE, chatId);

        // Продовжуємо обробку фото...
        try {
            AppPhoto photo = fileService.processPhoto(update.getMessage());
            if (photo != null) {
                String link = fileService.generateLink(photo.getId(), LinkType.GET_PHOTO);
                var answer = "Фото успішно завантажено! "
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


    // Змінюємо сигнатуру: приймаємо telegramUserId замість AppUser
    // Додаємо @Transactional(readOnly = true), щоб гарантувати свіжу сесію, але не дозволяти зміни
    @Transactional(readOnly = true)
    protected String checkPermissionError(Long telegramUserId) {
        if (telegramUserId == null) {
            log.error("checkPermissionError called with null telegramUserId");
            return "Помилка: не вдалося визначити користувача.";
        }

        // Перечитуємо користувача з БД всередині методу
        Optional<AppUser> optionalAppUser = appUserDAO.findByTelegramUserId(telegramUserId);

        if (optionalAppUser.isEmpty()) {
            log.error("User not found in checkPermissionError for telegramUserId: {}", telegramUserId);
            // Це не повинно трапитись, якщо findOrSaveAppUser спрацював раніше
            return "Помилка: користувач не знайдений.";
        }

        AppUser appUser = optionalAppUser.get(); // Тепер це мають бути актуальні дані
        var userState = appUser.getState();
        log.debug("Checking permissions for user ID: {}, isActive: {}, state: {}", appUser.getId(), appUser.isActive(), userState);

        // 1. Перевіряємо, чи користувач АКТИВНИЙ
        if (!appUser.isActive()) { // <<<=== Тепер перевіряємо актуальне значення isActive
            log.warn("Permission denied for user {}: not active.", telegramUserId);
            // Якщо користувач в процесі реєстрації (чекає email), даємо специфічне повідомлення
            if (WAIT_FOR_EMAIL_STATE.equals(userState)) {
                return "Будь ласка, завершіть реєстрацію, активувавши обліковий запис через email, перед завантаженням файлів.";
            } else {
                // Загальне повідомлення для неактивних
                return "Зареєструйтесь (/registration) або активуйте "
                        + "свій обліковий запис для завантаження контенту.";
            }
        }
        // 2. Якщо користувач АКТИВНИЙ, перевіряємо, чи він у БАЗОВОМУ стані
        else if (!BASIC_STATE.equals(userState)) {
            log.warn("Permission denied for user {}: is active but not in BASIC_STATE (current state: {}).", telegramUserId, userState);
            // Якщо активний, але не в базовому стані (напр., WAIT_FOR_EMAIL_STATE - хоча це мало б відсіятись раніше),
            // просимо скасувати команду.
            return "Скасуйте поточну команду за допомогою /cancel для надсилання файлів.";
        }

        // Якщо активний і в базовому стані - дозвіл надано
        log.debug("Permission granted for user {}", telegramUserId);
        return null; // Немає помилки
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

    // processServiceCommand залишається без змін (той, що ми виправили раніше)
    private String processServiceCommand(AppUser appUser, String cmd) {
        var serviceCommand = ServiceCommand.fromValue(cmd);
        // Перевіряємо, чи взагалі вдалося розпізнати команду
        if (serviceCommand == null) {
            // Якщо користувач в BASIC_STATE і надіслав не команду
            if (BASIC_STATE.equals(appUser.getState())) {
                log.debug("Received non-command text '{}' from user {} in BASIC_STATE", cmd, appUser.getTelegramUserId());
                return "Невідома команда! Щоб переглянути список доступних команд, введіть /help";
            } else {
                log.debug("Received non-command text '{}' from user {} in state {}", cmd, appUser.getTelegramUserId(), appUser.getState());
                return "Невідома команда! Щоб переглянути список доступних команд, введіть /help";
            }
        }

        // Обробка розпізнаних команд
        switch (serviceCommand) {
            case REGISTRATION:
                if (WAIT_FOR_EMAIL_STATE.equals(appUser.getState())) {
                    return "Ви вже в процесі реєстрації. Будь ласка, надішліть свій email або введіть /cancel.";
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

    // help залишається без змін (той, що ми виправили раніше)
    private String help() {
        return "Список доступних команд:\n"
                + "/cancel - скасування виконання поточної команди;\n"
                + "/registration - реєстрація користувача;\n"
                + "/resend_email - повторно надіслати лист активації (якщо ви в процесі реєстрації).";
    }

    // cancelProcess залишається без змін
    private String cancelProcess(AppUser appUser) {
        appUser.setState(BASIC_STATE);
        appUserDAO.save(appUser);
        log.info("User {} cancelled operation, state set to BASIC_STATE", appUser.getTelegramUserId());
        return "Команду скасовано!";
    }

    // findOrSaveAppUser залишається без змін
    private AppUser findOrSaveAppUser(Update update) {
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
                    .isActive(false)
                    .state(BASIC_STATE)
                    .build();
            return appUserDAO.save(transientAppUser);
        } else {
            AppUser existingUser = optionalAppUser.get();
            log.debug("Found existing AppUser with ID: {} for telegram user_id: {}", existingUser.getId(), telegramUser.getId());
            return existingUser;
        }
    }

    // saveRawData залишається без змін
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