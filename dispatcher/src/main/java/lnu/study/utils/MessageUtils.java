package lnu.study.utils;

import org.apache.logging.log4j.LogManager; // Для логування
import org.apache.logging.log4j.Logger;   // Для логування
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

@Component
public class MessageUtils {

    private static final Logger log = LogManager.getLogger(MessageUtils.class); // Логгер

    public static SendMessage generateSendMessageWithText(Update update, String text) {
        if (update == null || text == null) {
            log.warn("Update or text is null, cannot generate SendMessage.");
            return null;
        }

        Long chatId = null;
        Message messageFromUpdate = null;

        if (update.hasMessage()) {
            messageFromUpdate = update.getMessage();
            chatId = messageFromUpdate.getChatId();
        } else if (update.hasEditedMessage()) { // Додамо обробку редагованих повідомлень
            messageFromUpdate = update.getEditedMessage();
            chatId = messageFromUpdate.getChatId();
        } else if (update.hasChannelPost()) {
            messageFromUpdate = update.getChannelPost();
            chatId = messageFromUpdate.getChatId();
        } else if (update.hasEditedChannelPost()) {
            messageFromUpdate = update.getEditedChannelPost();
            chatId = messageFromUpdate.getChatId();
        } else if (update.hasCallbackQuery()) {
            if (update.getCallbackQuery().getMessage() != null) {
                // CallbackQuery може не мати 'message', якщо це, наприклад, inline-режим
                messageFromUpdate = (Message) update.getCallbackQuery().getMessage();
                chatId = messageFromUpdate.getChatId();
            } else {
                log.warn("CallbackQuery does not contain a message to extract chatId from. UpdateId: {}", update.getUpdateId());
                return null;
            }
        }
        // Можна додати інші типи Update, якщо потрібно (inline query, chosen inline result, etc.)

        if (chatId == null) {
            log.warn("Could not extract chatId from Update. UpdateId: {}", update.getUpdateId());
            return null;
        }

        var sendMessage = new SendMessage();
        sendMessage.setChatId(chatId.toString());
        sendMessage.setText(text);
        return sendMessage;
    }

}