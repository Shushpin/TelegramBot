package lnu.study.service.impl;

import lnu.study.dto.AudioToSendDTO;
import lnu.study.dto.DocumentToSendDTO;
import lnu.study.dto.PhotoToSendDTO;
import lnu.study.dto.VideoToSendDTO;
import lnu.study.service.ProducerService;
import lombok.extern.log4j.Log4j2;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument; // <<< НОВИЙ ІМПОРТ
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;   // <<< НОВИЙ ІМПОРТ
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery; // Додай цей імпорт
import static lnu.study.model.RabbitQueue.ANSWER_CALLBACK_QUEUE; // Додай цей імпорт

import static lnu.study.model.RabbitQueue.ANSWER_MESSAGE; // Переконайтеся, що цей імпорт правильний

@Log4j2
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
    @Override
    public void producerSendVideoDTO(VideoToSendDTO dto) { // Новий метод
        rabbitTemplate.convertAndSend(ANSWER_MESSAGE, dto); // Або інша черга, якщо потрібно
    }
    @Override
    public void producerAnswerCallbackQuery(String callbackQueryId, String text) {
        if (callbackQueryId == null || callbackQueryId.isEmpty()) {
            log.warn("Attempted to send AnswerCallbackQuery with null or empty callbackQueryId");
            return;
        }
        AnswerCallbackQuery answer = new AnswerCallbackQuery();
        answer.setCallbackQueryId(callbackQueryId);
        if (text != null && !text.isEmpty()) {
            answer.setText(text);
            // answer.setShowAlert(false); // За замовчуванням false. Встанови true, якщо хочеш показувати як спливаюче вікно.
            // answer.setCacheTime(Integer); // Можна встановити час кешування відповіді на клієнті
        }
        log.debug("Producing AnswerCallbackQuery (id: {}, text: '{}') to queue '{}'", callbackQueryId, text, ANSWER_CALLBACK_QUEUE);
        rabbitTemplate.convertAndSend(ANSWER_CALLBACK_QUEUE, answer);
    }
}