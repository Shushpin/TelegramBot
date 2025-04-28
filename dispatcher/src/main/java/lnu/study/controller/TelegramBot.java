package lnu.study.controller;

import jakarta.annotation.PostConstruct; // Важливо для Spring Boot 3+
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.starter.SpringWebhookBot; // для Webhook напровсяк
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;


@Component
public class TelegramBot extends TelegramLongPollingBot {

    private static final Logger log = LogManager.getLogger(TelegramBot.class);

    private final String botName;
    private final String botToken;
    private final TelegramBotsApi telegramBotsApi; // Для реєстрації

    @Autowired
    public TelegramBot(@Value("${bot.name}") String botName,
                       @Value("${bot.token}") String botToken) throws TelegramApiException {
        super(botToken); // Передаємо токен в конструктор суперкласу
        this.botName = botName;
        this.botToken = botToken; // Можна і не зберігати, якщо використовується тільки в getBotToken()
        this.telegramBotsApi = new TelegramBotsApi(DefaultBotSession.class);
        try {
            this.telegramBotsApi.registerBot(this);
            log.info("Telegram bot {} registered successfully!", botName);
        } catch (TelegramApiException e) {
            log.error("Error registering bot {}: {}", botName, e.getMessage());
            throw e; // Прокидуємо помилку далі, щоб Spring знав про проблему
        }
    }


    @Override
    public String getBotUsername() {
        return botName;
    }

    @Override
    public void onUpdateReceived(Update update) {
        // Перевіряємо, чи є повідомлення і чи є в ньому текст
        if (update.hasMessage() && update.getMessage().hasText()) {
            var message = update.getMessage();
            var chatId = message.getChatId().toString(); // Отримуємо ID чату для відповіді
            var messageText = message.getText();

            log.debug("Received message from chat_id={}: {}", chatId, messageText);

            // Тут можна додати просту відповідь для перевірки, пізніше не знадобиться (ЗАБРАТИ)
            SendMessage response = new SendMessage();
            response.setChatId(chatId);
            response.setText("Я отримав твоє повідомлення: " + messageText);
            try {
                execute(response); // Відправляємо відповідь
                log.debug("Sent response to chat_id={}", chatId);
            } catch (TelegramApiException e) {
                log.error("Failed to send message to chat_id={}: {}", chatId, e.getMessage());
            }
        } else {
            // Логуємо інші типи оновлень або ігноруємо їх
            log.trace("Received an update that is not a text message: {}", update);
        }
    }

}