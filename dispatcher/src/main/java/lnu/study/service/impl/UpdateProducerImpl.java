package lnu.study.service.impl;

import lnu.study.service.UpdateProducer;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.Update;

@Service
@Log4j2
public class UpdateProducerImpl implements UpdateProducer {
    @Override
    public void produce(String rabbitQueue, Update update) {
        log.debug(update.getMessage().getText());
    }
}
