package lnu.study.service.impl;

import lnu.study.service.ConsumerService;
import lnu.study.service.MainService;
import lnu.study.service.ProducerService;
import lombok.extern.log4j.Log4j2;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import static lnu.study.model.RabbitQueue.CALLBACK_QUERY_UPDATE;
import static lnu.study.model.RabbitQueue.AUDIO_MESSAGE_UPDATE; // Додай імпорт



import static lnu.study.model.RabbitQueue.*;

@Service
@Log4j2
public class ConsumerServiceImpl implements ConsumerService {
    private final MainService mainService;

    public ConsumerServiceImpl(MainService mainService) {
        this.mainService = mainService;
    }

    @Override
    @RabbitListener(queues = TEXT_MESSAGE_UPDATE)
    public void consumeTextMessageUpdates(Update update) {
        log.debug("NODE: Text message is received");
        mainService.processTextMessage(update);



    }

    @Override
    @RabbitListener(queues = DOC_MESSAGE_UPDATE)
    public void consumeDocMessageUpdates(Update update) {
        log.debug("NODE: Doc file is received");
        mainService.processDocMessage(update);


    }

    @Override
    @RabbitListener(queues = PHOTO_MESSAGE_UPDATE)
    public void consumePhotoMessageUpdates(Update update) {
        log.debug("NODE: Photo is received");
        mainService.processPhotoMessage(update);


    }

    @RabbitListener(queues = VOICE_MESSAGE_UPDATE)
    public void consumeVoiceMessageUpdates(Update update) {
        log.debug("NODE: Voice Message is received from RabbitMQ");
        mainService.processVoiceMessage(update);
    }
    @RabbitListener(queues = CALLBACK_QUERY_UPDATE)
    public void consumeCallbackQueryUpdate(@Payload Update update) {
        log.debug("NODE: Callback Query Update is received from queue {}", CALLBACK_QUERY_UPDATE);
        try {
            mainService.processFormatSelectionCallback(update);
        } catch (Exception e) {
            log.error("Error processing callback query update: {}", e.getMessage(), e);
            // Тут можна додати логіку для надсилання повідомлення про помилку користувачеві,
            // або просто логувати, залежно від політики обробки помилок.
        }
    }
    @RabbitListener(queues = AUDIO_MESSAGE_UPDATE)
    public void consumeAudioFileMessageUpdate(@Payload Update update) {
        log.debug("NODE: Audio File Message Update is received from queue {}", AUDIO_MESSAGE_UPDATE);
        try {
            mainService.processAudioFileMessage(update);
        } catch (Exception e) {
            log.error("Error processing audio file message update: {}", e.getMessage(), e);
        }
    }


}
