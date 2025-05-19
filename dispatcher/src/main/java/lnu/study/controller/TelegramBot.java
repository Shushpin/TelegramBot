package lnu.study.controller;

import jakarta.annotation.PostConstruct;
import lnu.study.dto.VideoToSendDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramWebhookBot;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands; // <--- ДОДАНО ІМПОРТ
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand; // <--- ДОДАНО ІМПОРТ
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery; // Додай імпорт
import org.telegram.telegrambots.meta.exceptions.TelegramApiException; // Додай імпорт


import java.io.ByteArrayInputStream;
// import java.io.Serializable; // Цей імпорт не використовується, можна видалити, якщо не потрібен для іншого
import java.util.ArrayList; // <--- ДОДАНО ІМПОРТ
import java.util.List;    // <--- ДОДАНО ІМПОРТ

@Component
public class TelegramBot extends TelegramWebhookBot {

    private static final Logger log = LogManager.getLogger(TelegramBot.class);

    private final String botName;
    // private final String botToken; // botToken передається в super(), тому поле може бути необов'язковим, якщо не використовується деінде
    private final String botUri;
    private final UpdateProcessor updateProcessor;

    @Autowired
    public TelegramBot(@Value("${bot.name}") String botName,
                       @Value("${bot.token}") String botToken, // botToken використовується для super(botToken)
                       @Value("${bot.uri}") String botUri,
                       @Lazy UpdateProcessor updateProcessor) {
        super(botToken); // Передача токена до батьківського класу
        this.botName = botName;
        // this.botToken = botToken; // Можна закоментувати, якщо не використовується напряму в цьому класі
        this.botUri = botUri;
        this.updateProcessor = updateProcessor;
        log.info("TelegramBot initialized with UpdateProcessor.");
    }

    @Override
    public BotApiMethod<?> onWebhookUpdateReceived(Update update) {
        log.debug("TelegramBot: Webhook update_id: {} received.", update.getUpdateId());
//         try {
//             updateProcessor.processUpdate(update); // Обробка оновлень зазвичай відбувається в WebHookController -> UpdateProcessor
//         } catch (Exception e) {
//             log.error("TelegramBot: Error processing update_id {} in UpdateProcessor from onWebhookUpdateReceived: {}", update.getUpdateId(), e.getMessage(), e);
//         }
        return null; // Для Webhook ботів, відповідь часто не потрібна або надсилається асинхронно
    }

    @PostConstruct
    public void init() {
        log.info("Initializing TelegramBot...");
        try {
            SetWebhook setWebhook = SetWebhook.builder().url(this.botUri).build();
            this.setWebhook(setWebhook);
            log.info("Webhook set successfully to URI: {}", this.botUri);

            setBotCommands(); // <--- ВИКЛИК МЕТОДУ ДЛЯ ВСТАНОВЛЕННЯ КОМАНД МЕНЮ

        } catch (TelegramApiException e) {
            log.error("Error during bot initialization (webhook or commands setup): {}", e.getMessage(), e);
        }
    }

    // Новий метод для встановлення команд меню
    private void setBotCommands() {
        List<BotCommand> commands = new ArrayList<>();
        commands.add(new BotCommand("/start", "Розпочати роботу"));
        commands.add(new BotCommand("/help", "Допомога та список команд"));
        commands.add(new BotCommand("/convert_file", "Увімкнути режим конвертації"));
        commands.add(new BotCommand("/generate_link", "Увімкнути режим файлообмінника"));
        commands.add(new BotCommand("/registration", "Реєстрація нового користувача"));
        commands.add(new BotCommand("/cancel", "Скасувати дію / Вийти з режиму"));
//        commands.add(new BotCommand("/resend_email", "Повторно надіслати лист активації"));

        SetMyCommands setMyCommandsAction = new SetMyCommands();
        setMyCommandsAction.setCommands(commands);
        // Для встановлення команд для всіх приватних чатів:
        // import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeAllPrivateChats;
        // setMyCommandsAction.setScope(new BotCommandScopeAllPrivateChats());

        try {
            this.execute(setMyCommandsAction); // Виконання запиту для встановлення команд
            log.info("Successfully set bot commands menu.");
        } catch (TelegramApiException e) {
            log.error("Error setting bot commands: " + e.getMessage(), e);
        }
    }

    @Override
    public String getBotUsername() {
        return this.botName;
    }

    // botToken вже передано в super(botToken) і доступний через getBotToken() батьківського класу,
    // тому окремий метод getBotToken() тут не потрібен, якщо він просто дублює функціонал батька.
    // Якщо ж є специфічна логіка, то його можна залишити.

    @Override
    public String getBotPath() {
        // Шлях, який використовується для вебхука, наприклад, "update" -> <bot_uri>/update
        // Переконайтесь, що він відповідає тому, що очікує ваш WebHookController
        return "update";
    }

    public void sendAnswerMessage(SendMessage sendMessage) {
        if (sendMessage == null) {
            log.error("Attempted to send a null SendMessage object.");
            return;
        }
        if (sendMessage.getChatId() == null || sendMessage.getChatId().isEmpty()) {
            log.error("Attempted to send SendMessage with null or empty chatId: {}", sendMessage.getText());
            return;
        }
        // Порожнє повідомлення може бути легітимним у деяких випадках (наприклад, для видалення клавіатури),
        // але для текстових повідомлень це зазвичай попередження.
        if (sendMessage.getText() == null || sendMessage.getText().isEmpty()) {
            log.warn("Attempted to send SendMessage with null or empty text to chatId {}.", sendMessage.getChatId());
        }

        try {
            execute(sendMessage);
            log.debug("Message sent to chat_id={}: '{}'", sendMessage.getChatId(), sendMessage.getText());
        } catch (TelegramApiException e) {
            log.error("Failed to send message to chat_id={}: {}", sendMessage.getChatId(), e.getMessage(), e);
        }
    }

    public void sendSpecificDocument(SendDocument sendDocument) {
        if (sendDocument == null) {
            log.warn("Attempted to send null SendDocument object.");
            return; }
        try {
            execute(sendDocument);
            log.debug("Successfully executed SendDocument to chat_id: {}", sendDocument.getChatId());
        } catch (TelegramApiException e) {
            log.error("Error executing SendDocument to chat_id {}: {}", sendDocument.getChatId(), e.getMessage(), e);
        }
    }

    public void sendSpecificPhoto(SendPhoto sendPhoto) {
        if (sendPhoto == null) {
            log.warn("Attempted to send null SendPhoto object.");
            return;
        }
        try {
            execute(sendPhoto);
            log.debug("Successfully executed SendPhoto to chat_id: {}", sendPhoto.getChatId());
        } catch (TelegramApiException e) {
            // Додав логування помилки, аналогічно іншим методам
            log.error("Error executing SendPhoto to chat_id {}: {}", sendPhoto.getChatId(), e.getMessage(), e);
        }
    }
    public void sendSpecificAudio(org.telegram.telegrambots.meta.api.methods.send.SendAudio sendAudio) {
        if (sendAudio == null) {
            log.warn("Attempted to send null SendAudio object.");
            return;
        }
        try {
            execute(sendAudio);
            log.debug("Successfully executed SendAudio to chat_id: {}", sendAudio.getChatId());
        } catch (TelegramApiException e) {
            log.error("Error executing SendAudio to chat_id {}: {}", sendAudio.getChatId(), e.getMessage(), e);
        }
    }
    public void sendVideo(VideoToSendDTO dto) {
        if (dto == null || dto.getChatId() == null || dto.getVideoBytes() == null || dto.getFileName() == null) {
            log.error("VideoToSendDTO is invalid: {}", dto);
            return;
        }
        SendVideo sendVideo = new SendVideo();
        sendVideo.setChatId(dto.getChatId());
        sendVideo.setVideo(new InputFile(new ByteArrayInputStream(dto.getVideoBytes()), dto.getFileName()));
        if (dto.getCaption() != null && !dto.getCaption().isEmpty()) {
            sendVideo.setCaption(dto.getCaption());
        }
        if (dto.getDuration() != null) sendVideo.setDuration(dto.getDuration());
        if (dto.getWidth() != null) sendVideo.setWidth(dto.getWidth());
        if (dto.getHeight() != null) sendVideo.setHeight(dto.getHeight());

        try {
            execute(sendVideo);
            log.debug("Video DTO sent to chat_id: {}", dto.getChatId());
        } catch (TelegramApiException e) {
            log.error("Failed to send Video DTO to chat_id {}: {}", dto.getChatId(), e.getMessage(), e);
        }
    }
    public void executeAnswerCallbackQuery(AnswerCallbackQuery answer) {
        if (answer == null) {
            log.warn("Attempted to execute a null AnswerCallbackQuery object.");
            return;
        }
        try {
            execute(answer); // Метод execute з батьківського класу TelegramWebhookBot
            log.debug("Successfully executed AnswerCallbackQuery for id: {}", answer.getCallbackQueryId());
        } catch (TelegramApiException e) {
            log.error("Failed to execute AnswerCallbackQuery for id {}: {}", answer.getCallbackQueryId(), e.getMessage(), e);
        }
    }
}