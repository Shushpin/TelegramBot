package lnu.study.controller;

import lnu.study.dto.AudioToSendDTO;
import lnu.study.service.UpdateProducer;
import lnu.study.utils.MessageUtils;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.Update;

import static lnu.study.model.RabbitQueue.*;

@Component
@Log4j2
public class UpdateProcessor {

    private TelegramBot telegramBot;
    private final UpdateProducer updateProducer;

    @Autowired
    public UpdateProcessor(UpdateProducer updateProducer) {
        this.updateProducer = updateProducer;
        log.info("UpdateProcessor initialized with UpdateProducer.");
    }

    @Autowired
    public void setTelegramBot(@Lazy TelegramBot telegramBot) {
        log.info("UpdateProcessor: Injecting TelegramBot instance.");
        this.telegramBot = telegramBot;
    }

    public void processUpdate(Update update) {
        if (update == null) {
            log.error("Received update is null");
            return;
        }
        if (update.hasMessage()) {
            distributeMessagesByType(update);
        } else if (update.hasCallbackQuery()) {
            log.warn("CallbackQuery received: {}", update.getCallbackQuery().getData());
            setUnsupportedMessageTypeView(update);
        } else {
            log.error("Unsupported update type or message is null: " + update);
            setUnsupportedMessageTypeView(update);
        }
    }

    private void distributeMessagesByType(Update update) {
        var message = update.getMessage();
        if (message.hasPhoto()) {
            log.info("Processing photo message from chat_id: {}", message.getChat().getId());
            processPhotoMessage(update);
        } else if (message.hasDocument()) {
            log.info("Processing document message from chat_id: {}", message.getChat().getId());
            processDocMessage(update);
        } else if (message.hasText()) {
            log.info("Processing text message from chat_id: {}: '{}'", message.getChat().getId(), message.getText());
            processTextMessage(update);
        } else if (message.hasVoice()) { // ПЕРЕД іншими, більш загальними перевірками типу файлу
            processVoiceMessage(update);
        } else {
            log.warn("Unsupported message content type from chat_id: {}", message.getChat().getId());
            setUnsupportedMessageTypeView(update);
        }
    }

    private void setUnsupportedMessageTypeView(Update update) {
        var sendMessage = MessageUtils.generateSendMessageWithText(update, "Unsupported message type!");
        if (sendMessage != null) {
            setView(sendMessage);
        } else {
            log.error("Could not generate 'Unsupported message type' response for update_id: {}", update.getUpdateId());
        }
    }

    public void setView(SendMessage sendMessage) {
        log.debug("UpdateProcessor: Attempting to send message via TelegramBot to chat_id: {}", sendMessage.getChatId());
        if (this.telegramBot != null) {
            telegramBot.sendAnswerMessage(sendMessage);
            log.info("UpdateProcessor: Message passed to TelegramBot for chat_id: {}", sendMessage.getChatId());
        } else {
            log.error("UpdateProcessor: CRITICAL - TelegramBot instance is NULL. Cannot send message to chat_id: {}", sendMessage.getChatId());
            throw new IllegalStateException("TelegramBot instance is null in UpdateProcessor. Cannot send message.");
        }
    }
    private void processVoiceMessage(Update update) {
        updateProducer.produceVoiceMessage(update); // Використовуємо новий метод продюсера
    }

    private void processPhotoMessage(Update update) {
        updateProducer.produce(PHOTO_MESSAGE_UPDATE, update);
        log.info("Photo message update for chat_id {} sent to RabbitMQ queue: {}", update.getMessage().getChatId(), PHOTO_MESSAGE_UPDATE);
    }

    private void processDocMessage(Update update) {
        updateProducer.produce(DOC_MESSAGE_UPDATE, update);
        log.info("Document message update for chat_id {} sent to RabbitMQ queue: {}", update.getMessage().getChatId(), DOC_MESSAGE_UPDATE);
    }

    private void processTextMessage(Update update) {
        updateProducer.produce(TEXT_MESSAGE_UPDATE, update);
        log.info("Text message update for chat_id {} sent to RabbitMQ queue: {}", update.getMessage().getChatId(), TEXT_MESSAGE_UPDATE);
    }

    public void setView(SendDocument sendDocument) {
        log.debug("UpdateProcessor: Attempting to send SendDocument via TelegramBot to chat_id: {}", sendDocument.getChatId());
        if (this.telegramBot != null) {
            telegramBot.sendSpecificDocument(sendDocument);
            log.info("UpdateProcessor: SendDocument passed to TelegramBot for chat_id: {}", sendDocument.getChatId());
        } else {
            log.error("UpdateProcessor: CRITICAL - TelegramBot instance is NULL. Cannot send SendDocument to chat_id: {}", sendDocument.getChatId());
        }
    }

    public void setView(SendPhoto sendPhoto) {
        log.debug("UpdateProcessor: Attempting to send SendPhoto via TelegramBot to chat_id: {}", sendPhoto.getChatId());
        if (this.telegramBot != null) {
            telegramBot.sendSpecificPhoto(sendPhoto);
            log.info("UpdateProcessor: SendPhoto passed to TelegramBot for chat_id: {}", sendPhoto.getChatId());
        } else {
            log.error("UpdateProcessor: CRITICAL - TelegramBot instance is NULL. Cannot send SendPhoto to chat_id: {}", sendPhoto.getChatId());
        }
    }
    public void setView(org.telegram.telegrambots.meta.api.methods.send.SendAudio sendAudio) { // Додаємо імпорт, якщо потрібно
        log.debug("UpdateProcessor: Attempting to send SendAudio via TelegramBot to chat_id: {}", sendAudio.getChatId());
        if (this.telegramBot != null) {
            telegramBot.sendSpecificAudio(sendAudio); // Виклик нового методу в TelegramBot
            log.info("UpdateProcessor: SendAudio passed to TelegramBot for chat_id: {}", sendAudio.getChatId());
        } else {
            log.error("UpdateProcessor: CRITICAL - TelegramBot instance is NULL. Cannot send SendAudio to chat_id: {}", sendAudio.getChatId());
            // Можливо, варто кидати виняток, якщо telegramBot є null, щоб швидше виявити проблему
            // throw new IllegalStateException("TelegramBot instance is null in UpdateProcessor. Cannot send SendAudio.");
        }
    }

}
