package lnu.study.controller;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;


@Component
public class TelegramBot extends TelegramLongPollingBot {

    private static final Logger log = LogManager.getLogger(TelegramBot.class);

    private final String botName;
    private final TelegramBotsApi telegramBotsApi;
    private final UpdateController updateController; // Зробимо final

    @PostConstruct
    public void init(){
        updateController.registerBot(this);
        log.info("TelegramBot instance registered in UpdateController.");
    }

    @Autowired
    public TelegramBot(@Value("${bot.name}") String botName,
                       @Value("${bot.token}") String botToken,
                       UpdateController updateController) throws TelegramApiException {
        super(botToken);
        this.botName = botName;
        this.updateController = updateController; // Ініціалізуємо final поле
        this.telegramBotsApi = new TelegramBotsApi(DefaultBotSession.class);
        try {
            this.telegramBotsApi.registerBot(this);
            log.info("Telegram bot {} registered successfully!", botName);
        } catch (TelegramApiException e) {
            log.error("Error registering bot {}: {}", botName, e.getMessage());
            throw e;
        }
    }

    @Override
    public String getBotUsername() {
        return botName;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update != null) {
            log.trace("Update received: {}", update.getUpdateId());
            // Просто передаємо обробку в контролер
            updateController.processUpdate(update);
        } else {
            log.warn("Received null update");
        }

        // !!! БЛОК З НЕГАЙНОЮ ВІДПОВІДДЮ ВИДАЛЕНО !!!
    }

    // !!! РЕАЛІЗАЦІЯ МЕТОДУ ВІДПРАВКИ ВІДПОВІДІ !!!
    public void sendAnswerMessage(SendMessage sendMessage) {
        if (sendMessage != null && sendMessage.getChatId() != null && sendMessage.getText() != null) {
            try {
                // Використовуємо метод execute() з батьківського класу TelegramLongPollingBot
                execute(sendMessage);
                log.debug("Sent answer message to chat_id={}", sendMessage.getChatId());
            } catch (TelegramApiException e) {
                // Логуємо помилку разом зі стектрейсом для кращої діагностики
                log.error("Failed to send answer message to chat_id={}: {}", sendMessage.getChatId(), e.getMessage(), e);
            }
        } else {
            // Логуємо, якщо прийшов некоректний об'єкт SendMessage
            log.error("Attempted to send a null or incomplete SendMessage object: {}", sendMessage);
        }
    }
}