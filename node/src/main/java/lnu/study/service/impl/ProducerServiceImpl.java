package lnu.study.service.impl;

import lnu.study.dto.AudioToSendDTO;
import lnu.study.dto.DocumentToSendDTO;
import lnu.study.dto.PhotoToSendDTO;
import lnu.study.service.ProducerService;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument; // <<< НОВИЙ ІМПОРТ
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;   // <<< НОВИЙ ІМПОРТ

import static lnu.study.model.RabbitQueue.ANSWER_MESSAGE; // Переконайтеся, що цей імпорт правильний

@Service
public class ProducerServiceImpl implements ProducerService {
    private final RabbitTemplate rabbitTemplate;

    public ProducerServiceImpl(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void producerAnswer(SendMessage sendMessage) {
        // Відправляємо в ту саму чергу, що й раніше
        rabbitTemplate.convertAndSend(ANSWER_MESSAGE, sendMessage);
    }

    @Override
    public void producerSendDocument(SendDocument sendDocument) { // <<< НОВА РЕАЛІЗАЦІЯ
        // Відправляємо в ту саму чергу ANSWER_MESSAGE
        // RabbitMQ (з Jackson message converter) має впоратися з серіалізацією SendDocument
        rabbitTemplate.convertAndSend(ANSWER_MESSAGE, sendDocument);
    }

    @Override
    public void producerSendPhoto(SendPhoto sendPhoto) {         // <<< НОВА РЕАЛІЗАЦІЯ
        // Відправляємо в ту саму чергу ANSWER_MESSAGE
        rabbitTemplate.convertAndSend(ANSWER_MESSAGE, sendPhoto);
    }

    // <<< НОВА РЕАЛІЗАЦІЯ >>>
    @Override
    public void producerSendPhotoDTO(PhotoToSendDTO photoToSendDTO) {
        // Відправляємо DTO в ту саму чергу ANSWER_MESSAGE.
        // RabbitMQ (з Jackson message converter) має серіалізувати PhotoToSendDTO.
        rabbitTemplate.convertAndSend(ANSWER_MESSAGE, photoToSendDTO);
    }

    @Override
    public void producerSendDocumentDTO(DocumentToSendDTO documentToSendDTO) {
        rabbitTemplate.convertAndSend(ANSWER_MESSAGE, documentToSendDTO);
    }

    @Override
    public void producerSendAudioDTO(AudioToSendDTO audioToSendDTO) {

        rabbitTemplate.convertAndSend(ANSWER_MESSAGE, audioToSendDTO);
    }
    // Якщо б ви обрали один загальний метод:
    /*
    @Override
    public void producerSendBotApiMethod(org.telegram.telegrambots.meta.api.methods.BotApiMethod<?> method) {
        rabbitTemplate.convertAndSend(ANSWER_MESSAGE, method);
    }
    */
}