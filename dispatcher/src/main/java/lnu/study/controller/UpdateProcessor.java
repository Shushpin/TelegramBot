package lnu.study.controller; // Або правильний пакет

import lnu.study.service.UpdateProducer;
import lnu.study.utils.MessageUtils; // Імпорт класу
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;

import static lnu.study.model.RabbitQueue.*;

@Component
@Log4j2
public class UpdateProcessor {

    private TelegramBot telegramBot;
    // Поле messageUtils може бути не потрібне, якщо єдиний метод, що використовується - статичний
    // private final MessageUtils messageUtils; // <<< МОЖЛИВО, НЕ ПОТРІБНЕ
    private final UpdateProducer updateProducer;

    @Autowired
    public UpdateProcessor(/*MessageUtils messageUtils,*/ UpdateProducer updateProducer) { // <<< messageUtils може бути видалено з конструктора
        // this.messageUtils = messageUtils; // <<<
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
            // Обробка CallbackQuery (приклад)
            // updateProducer.produce(CALLBACK_QUERY_UPDATE, update);
            // Повідомлення про непідтримуваний тип може бути недоречним для callback_query,
            // залежно від логіки. Можна просто логувати або нічого не робити, якщо немає обробника.
            // Для прикладу, залишимо як є, але це місце для покращення.
            setUnsupportedMessageTypeView(update);
        } else {
            log.error("Unsupported update type or message is null: " + update);
            // Спроба відправити помилку, якщо це можливо
            setUnsupportedMessageTypeView(update); // update може не містити інформації для відповіді
        }
    }

    private void distributeMessagesByType(Update update) {
        var message = update.getMessage(); // update.hasMessage() вже перевірено
        if (message.hasPhoto()) {
            log.info("Processing photo message from chat_id: {}", message.getChat().getId());
            processPhotoMessage(update);
        } else if (message.hasDocument()) {
            log.info("Processing document message from chat_id: {}", message.getChat().getId());
            processDocMessage(update);
        } else if (message.hasText()) {
            log.info("Processing text message from chat_id: {}: '{}'", message.getChat().getId(), message.getText());
            processTextMessage(update);
        } else {
            log.warn("Unsupported message content type from chat_id: {}", message.getChat().getId());
            setUnsupportedMessageTypeView(update);
        }
    }

    private void setUnsupportedMessageTypeView(Update update) {
        // Викликаємо статичний метод MessageUtils
        // І передаємо весь 'update' об'єкт
        var sendMessage = MessageUtils.generateSendMessageWithText(update, // <<< ВИПРАВЛЕНО: ВИКЛИК СТАТИЧНОГО МЕТОДУ
                "Unsupported message type!");
        if (sendMessage != null) { // Перевіряємо, чи MessageUtils зміг створити повідомлення
            setView(sendMessage);
        } else {
            log.error("Could not generate 'Unsupported message type' response for update_id: {}", update.getUpdateId());
        }
    }

    private void setFileIsReceivedView(Update update) {
        // Викликаємо статичний метод MessageUtils
        // І передаємо весь 'update' об'єкт
//        var sendMessage = MessageUtils.generateSendMessageWithText(update, // <<< ВИПРАВЛЕНО: ВИКЛИК СТАТИЧНОГО МЕТОДУ
//                "We are working with your file! Loading...");
//        if (sendMessage != null) { // Перевіряємо результат
//            setView(sendMessage);
//        } else {
//            log.error("Could not generate 'File is received' response for update_id: {}", update.getUpdateId());
//        }
    }

    public void setView(SendMessage sendMessage) {
        log.debug("UpdateProcessor: Attempting to send message via TelegramBot to chat_id: {}", sendMessage.getChatId());
        if (this.telegramBot != null) {
            // Тут помилки бути не повинно, якщо telegramBot коректно інжектований
            // і метод sendAnswerMessage існує в TelegramBot
            telegramBot.sendAnswerMessage(sendMessage); // <<< Цей рядок має тепер працювати
            log.info("UpdateProcessor: Message passed to TelegramBot for chat_id: {}", sendMessage.getChatId());
        } else {
            log.error("UpdateProcessor: CRITICAL - TelegramBot instance is NULL. Cannot send message to chat_id: {}. Message: '{}'", sendMessage.getChatId(), sendMessage.getText());
            throw new IllegalStateException("TelegramBot instance is null in UpdateProcessor. Cannot send message.");
        }
    }

    private void processPhotoMessage(Update update) {
        setFileIsReceivedView(update);
        updateProducer.produce(PHOTO_MESSAGE_UPDATE, update);
        log.info("Photo message update for chat_id {} sent to RabbitMQ queue: {}", update.getMessage().getChatId(), PHOTO_MESSAGE_UPDATE);
    }

    private void processDocMessage(Update update) {
        setFileIsReceivedView(update);
        updateProducer.produce(DOC_MESSAGE_UPDATE, update);
        log.info("Document message update for chat_id {} sent to RabbitMQ queue: {}", update.getMessage().getChatId(), DOC_MESSAGE_UPDATE);
    }

    private void processTextMessage(Update update) {
        updateProducer.produce(TEXT_MESSAGE_UPDATE, update);
        log.info("Text message update for chat_id {} sent to RabbitMQ queue: {}", update.getMessage().getChatId(), TEXT_MESSAGE_UPDATE);
    }
}