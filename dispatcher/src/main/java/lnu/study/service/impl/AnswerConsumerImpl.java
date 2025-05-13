package lnu.study.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lnu.study.controller.UpdateProcessor;
import lnu.study.dto.DocumentToSendDTO;
import lnu.study.dto.PhotoToSendDTO; // <<< НОВИЙ ІМПОРТ
import lnu.study.service.AnswerConsumer;
import lombok.extern.log4j.Log4j2;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile; // <<< НОВИЙ ІМПОРТ

import java.io.ByteArrayInputStream; // <<< НОВИЙ ІМПОРТ
import java.io.IOException;

import static lnu.study.model.RabbitQueue.ANSWER_MESSAGE;

@Log4j2
@Service
public class AnswerConsumerImpl implements AnswerConsumer {

    private final UpdateProcessor updateProcessor;
    private final ObjectMapper objectMapper;

    public AnswerConsumerImpl(UpdateProcessor updateProcessor, ObjectMapper objectMapper) {
        this.updateProcessor = updateProcessor;
        this.objectMapper = objectMapper;
    }

    @Override
    @RabbitListener(queues = ANSWER_MESSAGE)
    public void consume(Message message) {
        if (message == null || message.getBody() == null || message.getMessageProperties() == null) {
            log.error("Received invalid message from RabbitMQ: {}", message);
            return;
        }

        String typeId = (String) message.getMessageProperties().getHeaders().get("__TypeId__");

        if (typeId == null) {
            log.warn("Received message without __TypeId__ header. Body preview: {}", new String(message.getBody()).substring(0, Math.min(100, message.getBody().length)));
            return;
        }

        log.debug("Received message with __TypeId__: {}", typeId);

        try {
            byte[] body = message.getBody();
            if (typeId.equals(SendMessage.class.getName())) {
                SendMessage sendMessage = objectMapper.readValue(body, SendMessage.class);
                log.debug("Successfully deserialized message to SendMessage. Text: '{}'", sendMessage.getText());
                updateProcessor.setView(sendMessage); // Тільки ОДИН виклик для SendMessage
            } else if (typeId.equals(PhotoToSendDTO.class.getName())) {
                PhotoToSendDTO photoDTO = objectMapper.readValue(body, PhotoToSendDTO.class);
                log.debug("Successfully deserialized message to PhotoToSendDTO for chatId: {}. Filename: {}", photoDTO.getChatId(), photoDTO.getFileName());

                SendPhoto newSendPhoto = new SendPhoto();
                newSendPhoto.setChatId(photoDTO.getChatId());
                // Переконайтесь, що photoDTO.getPhotoBytes() повертає коректний масив байтів
                InputFile inputFilePhoto = new InputFile(new ByteArrayInputStream(photoDTO.getPhotoBytes()), photoDTO.getFileName());
                newSendPhoto.setPhoto(inputFilePhoto);
                if (photoDTO.getCaption() != null && !photoDTO.getCaption().isEmpty()) {
                    newSendPhoto.setCaption(photoDTO.getCaption());
                }
                log.debug("Created new SendPhoto object. Attempting to send via UpdateProcessor.");
                updateProcessor.setView(newSendPhoto);
            } else if (typeId.equals(DocumentToSendDTO.class.getName())) {
                DocumentToSendDTO documentDTO = objectMapper.readValue(body, DocumentToSendDTO.class);
                log.debug("Successfully deserialized message to DocumentToSendDTO for chatId: {}. Filename: {}", documentDTO.getChatId(), documentDTO.getFileName());

                SendDocument newSendDocument = new SendDocument();
                newSendDocument.setChatId(documentDTO.getChatId());
                // Переконайтесь, що documentDTO.getDocumentBytes() повертає коректний масив байтів
                InputFile inputFileDoc = new InputFile(new ByteArrayInputStream(documentDTO.getDocumentBytes()), documentDTO.getFileName());
                newSendDocument.setDocument(inputFileDoc);
                if (documentDTO.getCaption() != null && !documentDTO.getCaption().isEmpty()) {
                    newSendDocument.setCaption(documentDTO.getCaption());
                }
                log.debug("Created new SendDocument object. Attempting to send via UpdateProcessor.");
                updateProcessor.setView(newSendDocument);
            }
            // Видаліть застарілі блоки для SendDocument.class.getName() та SendPhoto.class.getName(), якщо вони більше не потрібні.
            // Наприклад, якщо SendPhoto.class.getName() більше не використовується, його блок теж можна прибрати.
            else {
                log.warn("Received message with unhandled or unexpected __TypeId__: {}. Body preview: {}", typeId, new String(body).substring(0, Math.min(100, body.length)));
            }
        } catch (IOException e) {
            log.error("Failed to deserialize message with __TypeId__ '{}': {}. Message body preview: {}", typeId, e.getMessage(), new String(message.getBody()).substring(0, Math.min(100, message.getBody().length)), e);
        } catch (Exception e) {
            log.error("Unexpected error processing message with __TypeId__ '{}': {}. Message body preview: {}", typeId, e.getMessage(), new String(message.getBody()).substring(0, Math.min(100, message.getBody().length)), e);
        }
    }
}