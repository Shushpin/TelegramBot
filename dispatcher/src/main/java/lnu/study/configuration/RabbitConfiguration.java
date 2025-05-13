package lnu.study.configuration;

import com.fasterxml.jackson.databind.DeserializationFeature; // <<< ДОДАЙ ЦЕЙ ІМПОРТ
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static lnu.study.model.RabbitQueue.*;

@Configuration
public class RabbitConfiguration {
    @Bean
    public MessageConverter jsonmessageConverter() {
        // Можна залишити як є, або передати налаштований objectMapper:
        // return new Jackson2JsonMessageConverter(objectMapper());
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        // <<< --- ДОДАЙ ЦЕЙ РЯДОК --- >>>
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // <<< ---------------------- >>>
        return objectMapper;
    }

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
    @Bean
    public Queue answerMessageQueue() {
        return new Queue(ANSWER_MESSAGE);
    }
}