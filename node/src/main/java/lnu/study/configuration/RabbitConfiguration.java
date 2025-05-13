package lnu.study.configuration;

// Додайте імпорт Queue
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Переконайтесь, що константи імпортуються правильно
import static lnu.study.model.RabbitQueue.*;

@Configuration
public class RabbitConfiguration {
    @Bean
    public MessageConverter jsonmessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // Додайте біни для черг, які слухає node
    @Bean
    public Queue textMessageQueue() {
        return new Queue(TEXT_MESSAGE_UPDATE);
    }

    @Bean
    public Queue docMessageQueue() {
        return new Queue(DOC_MESSAGE_UPDATE);
    }

    @Bean
    public Queue photoMessageQueue() {
        return new Queue(PHOTO_MESSAGE_UPDATE);
    }

    // Додайте бін для черги, в яку node надсилає відповідь
    @Bean
    public Queue answerMessageQueue() {
        return new Queue(ANSWER_MESSAGE);
    }
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter); // <<< Встановлюємо конвертер
        return rabbitTemplate;
    }
}