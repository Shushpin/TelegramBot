package lnu.study.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lnu.study.controller.UpdateProcessor;
import lnu.study.dto.AudioToSendDTO;
import lnu.study.dto.DocumentToSendDTO;
import lnu.study.dto.PhotoToSendDTO;
import lnu.study.dto.VideoToSendDTO;
import lnu.study.service.AnswerConsumer;
import lombok.extern.log4j.Log4j2;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;   // Потрібен цей імпорт
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;

import java.io.ByteArrayInputStream;
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
                log.debug("Successfully deserialized to SendMessage. Text: '{}'", sendMessage.getText());
                updateProcessor.setView(sendMessage);
            } else if (typeId.equals(PhotoToSendDTO.class.getName())) {
                PhotoToSendDTO photoDTO = objectMapper.readValue(body, PhotoToSendDTO.class);
                log.debug("Deserialized PhotoToSendDTO: {}", photoDTO.getFileName());

                SendPhoto sendPhoto = new SendPhoto();
                sendPhoto.setChatId(photoDTO.getChatId());
                sendPhoto.setPhoto(new InputFile(new ByteArrayInputStream(photoDTO.getPhotoBytes()), photoDTO.getFileName()));
                if (photoDTO.getCaption() != null && !photoDTO.getCaption().isEmpty()) {
                    sendPhoto.setCaption(photoDTO.getCaption());
                }
                updateProcessor.setView(sendPhoto); // ВИПРАВЛЕНО: викликаємо setView(SendPhoto)
            } else if (typeId.equals(DocumentToSendDTO.class.getName())) {
                DocumentToSendDTO documentDTO = objectMapper.readValue(body, DocumentToSendDTO.class);
                log.debug("Deserialized DocumentToSendDTO: {}", documentDTO.getFileName());

                SendDocument sendDocument = new SendDocument();
                sendDocument.setChatId(documentDTO.getChatId());
                sendDocument.setDocument(new InputFile(new ByteArrayInputStream(documentDTO.getDocumentBytes()), documentDTO.getFileName()));
                if (documentDTO.getCaption() != null && !documentDTO.getCaption().isEmpty()) {
                    sendDocument.setCaption(documentDTO.getCaption());
                }
                updateProcessor.setView(sendDocument); // ВИПРАВЛЕНО: викликаємо setView(SendDocument)
            } else if (typeId.equals(AudioToSendDTO.class.getName())) { // <<< НОВИЙ БЛОК, ВИПРАВЛЕНИЙ
                AudioToSendDTO audioDTO = objectMapper.readValue(body, AudioToSendDTO.class);
                log.debug("Deserialized AudioToSendDTO: {}", audioDTO.getFileName());

                SendAudio sendAudio = new SendAudio();
                sendAudio.setChatId(audioDTO.getChatId());
                sendAudio.setAudio(new InputFile(new ByteArrayInputStream(audioDTO.getAudioBytes()), audioDTO.getFileName()));
                if (audioDTO.getCaption() != null && !audioDTO.getCaption().isEmpty()) {
                    sendAudio.setCaption(audioDTO.getCaption());
                }
                // Тут можна додати інші параметри для SendAudio, якщо вони є в DTO (duration, performer, title)
                // sendAudio.setDuration(audioDTO.getDuration());
                // sendAudio.setPerformer(audioDTO.getPerformer());
                // sendAudio.setTitle(audioDTO.getTitle());

                updateProcessor.setView(sendAudio); // ВИПРАВЛЕНО: викликаємо setView(SendAudio)
            }
            else if (typeId.equals(VideoToSendDTO.class.getName())) { // <<< НОВИЙ БЛОК
                VideoToSendDTO videoDTO = objectMapper.readValue(body, VideoToSendDTO.class);
                log.debug("Successfully deserialized to VideoToSendDTO. Filename: {}", videoDTO.getFileName());
                updateProcessor.sendVideo(videoDTO);
            }
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