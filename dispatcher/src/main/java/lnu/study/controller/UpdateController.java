package lnu.study.controller;

import lnu.study.service.UpdateProducer;
import lnu.study.utils.MessageUtils;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;

import static lnu.study.model.RabbitQueue.*;

@Component
@Log4j2
public class UpdateController {

    private TelegramBot telegramBot;
    private final MessageUtils messageUtils;
    private final UpdateProducer updateProducer;

    public UpdateController(MessageUtils messageUtils,UpdateProducer updateProducer) {
        this.messageUtils = messageUtils;
        this.updateProducer = updateProducer;
    }

    public void registerBot(TelegramBot telegramBot) {
        this.telegramBot = telegramBot;
    }

    public void processUpdate(Update update) {
        if(update == null) {
            log.error("Received update is null");
        }

        if(update.getMessage() != null) {
            distributeMessagesByType(update);
        }else{
            log.error("Unsupported message type is received:" + update);
        }
    }

    // В класі lnu.study.controller.UpdateController

    private void distributeMessagesByType(Update update) {
        var message = update.getMessage();
        // Перевіряємо конкретні типи першими, використовуючи hasX() методи
        if (message.hasPhoto()) { // Чи є фото?
            processPhotoMessage(update);
        } else if (message.hasDocument()) { // Чи є документ?
            processDocMessage(update);
        } else if (message.hasText()) { // Чи є текст?
            processTextMessage(update);
        } else {
            // Якщо нічого з переліченого, то тип не підтримується
            setUnsupportedMessageTypeView(update);
        }
    }

    private void setUnsupportedMessageTypeView(Update update) {
        var sendMessage = messageUtils.generateSendMessageWithText(update,
                "Unsupported message type!");
        setView(sendMessage);
    }

    private void setFileIsReceivedView(Update update) {
        var sendMessage = messageUtils.generateSendMessageWithText(update,
                "We are working with your file! Loading...");
        setView(sendMessage);
    }

    public void setView(SendMessage sendMessage) {
        telegramBot.sendAnswerMessage(sendMessage);
    }

    private void processPhotoMessage(Update update) {
        updateProducer.produce(PHOTO_MESSAGE_UPDATE, update);

    }

    private void processDocMessage(Update update) {
        updateProducer.produce(DOC_MESSAGE_UPDATE, update);

    }

    private void processTextMessage(Update update) {
        updateProducer.produce(TEXT_MESSAGE_UPDATE, update);
    }

}
