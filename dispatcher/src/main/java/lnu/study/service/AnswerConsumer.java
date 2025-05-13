package lnu.study.service;

import org.springframework.amqp.core.Message;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

public interface AnswerConsumer {
    void consume(Message message);

}
