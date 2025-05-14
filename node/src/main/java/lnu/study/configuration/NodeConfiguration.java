package lnu.study.configuration;

import lnu.study.utils.CryptoTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class NodeConfiguration {
    @Value("${salt]")
    private String salt;

    @Bean
    public CryptoTool getCryptoTool() {
        return new CryptoTool(salt);
    }

    @Bean // Оголошення біна RestTemplate
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
