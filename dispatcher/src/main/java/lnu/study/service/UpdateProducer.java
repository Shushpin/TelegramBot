package lnu.study.service;

import org.telegram.telegrambots.meta.api.objects.Update;

public interface UpdateProducer {
    void produce(String rabbitQueue, Update update);
    void produceVoiceMessage(Update update);

}
