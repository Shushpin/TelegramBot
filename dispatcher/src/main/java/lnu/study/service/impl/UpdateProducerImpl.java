package lnu.study.service.impl;

import lnu.study.service.UpdateProducer;
import lombok.extern.log4j.Log4j2;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.Update;

@Service
@Log4j2
public class UpdateProducerImpl implements UpdateProducer {

    private final RabbitTemplate rabbitTemplate;

    public UpdateProducerImpl(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }
    //1test
    @Override
    public void produce(String rabbitQueue, Update update) {
        log.debug(update.getMessage().getText());
        log.info("Intending to produce update_id={} to queue '{}'", update.getUpdateId(), rabbitQueue); //hai bude
        rabbitTemplate.convertAndSend(rabbitQueue, update);
    }
}
