package lnu.study.controller;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramWebhookBot;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import java.io.Serializable;

@Component
public class TelegramBot extends TelegramWebhookBot {

    private static final Logger log = LogManager.getLogger(TelegramBot.class);

    private final String botName;
    private final String botToken;
    private final String botUri;
    private final UpdateProcessor updateProcessor;

    @Autowired
    public TelegramBot(@Value("${bot.name}") String botName,
                       @Value("${bot.token}") String botToken,
                       @Value("${bot.uri}") String botUri,
                       @Lazy UpdateProcessor updateProcessor) {
        super(botToken);
        this.botName = botName;
        this.botToken = botToken;
        this.botUri = botUri;
        this.updateProcessor = updateProcessor;
        log.info("TelegramBot initialized with UpdateProcessor.");
    }

    @Override
    public BotApiMethod<?> onWebhookUpdateReceived(Update update) {
        log.debug("TelegramBot: Webhook update_id: {} received.", update.getUpdateId());
//         try {
//             updateProcessor.processUpdate(update);
//         } catch (Exception e) {
//             log.error("TelegramBot: Error processing update_id {} in UpdateProcessor from onWebhookUpdateReceived: {}", update.getUpdateId(), e.getMessage(), e);
//         }
        return null;
    }

    @PostConstruct
    public void init() {
        log.info("Initializing TelegramBot with webhook URI: {}", botUri);
        try {
            SetWebhook setWebhook = SetWebhook.builder().url(this.botUri).build();
            this.setWebhook(setWebhook);
            log.info("Webhook set successfully to URI: {}", this.botUri);
        } catch (TelegramApiException e) {
            log.error("Error setting webhook: {}", e.getMessage(), e);
        }
    }

    @Override
    public String getBotUsername() {
        return this.botName;
    }

    @Override
    public String getBotPath() {
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
        if (sendPhoto == null) { return; }
        try {
            execute(sendPhoto);
            log.debug("Successfully executed SendPhoto to chat_id: {}", sendPhoto.getChatId());
        } catch (TelegramApiException e) { }
    }
}
