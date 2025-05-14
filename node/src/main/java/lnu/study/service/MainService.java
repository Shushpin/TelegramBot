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
}
