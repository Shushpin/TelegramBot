package lnu.study.configuration;

import lnu.study.utils.CryptoTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;


@Configuration
public class NodeConfiguration {
    @Value("${salt}")
    private String salt;

    @Value("${token}")
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