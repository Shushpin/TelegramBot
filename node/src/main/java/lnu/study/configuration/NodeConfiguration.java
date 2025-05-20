package lnu.study.configuration;

import lnu.study.utils.CryptoTool; // Ти використовуєш CryptoTool з common-utils
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
// Якщо будеш використовувати @Getter від Lombok для поля, додай імпорт:
// import lombok.Getter;

@Configuration
public class NodeConfiguration {
    @Value("${salt}") // Виправлена одруківка
    private String salt;

    @Value("${token}") // Зчитуємо властивість 'token'
    private String botToken;

    // Додаємо getter для botToken
    public String getBotToken() {
        return botToken;
    }

    // Getter для salt, якщо потрібен в інших частинах програми
    public String getSalt() {
        return salt;
    }

    @Bean
    public CryptoTool getCryptoTool() {
        return new CryptoTool(salt);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}