package lnu.study.service;

import org.springframework.amqp.core.Message;

public interface AnswerConsumer {
    void consume(Message message);

}
