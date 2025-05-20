package lnu.study.service;

import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.Update;

public interface MainService {
    void processTextMessage(Update update);
    void processDocMessage(Update update);
    void processPhotoMessage(Update update);
    void processVoiceMessage(Update update);
    void processAudioMessage(Update update);
    void processVideoMessage(Update update);

    // ... інші методи класу ...
    // Розкоментуй, якщо цей метод визначений в інтерфейсі MainService
    @Transactional // Додай, якщо потрібна робота з БД, наприклад, зміна стану користувача
    void processCallbackQuery(Update update);

    void processFormatSelectionCallback(Update update);
    void processAudioFileMessage(Update update);
}
