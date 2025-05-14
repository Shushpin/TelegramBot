package lnu.study.service;

import lnu.study.dto.AudioToSendDTO;
import lnu.study.dto.DocumentToSendDTO;
import lnu.study.dto.PhotoToSendDTO;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument; // <<< НОВИЙ ІМПОРТ
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;   // <<< НОВИЙ ІМПОРТ
// Можна також додати SendAudio, SendVideo, якщо плануєте їх конвертувати

public interface ProducerService {

    void producerAnswer(SendMessage sendMessage);

    void producerSendDocument(SendDocument sendDocument); // <<< НОВИЙ МЕТОД

    void producerSendPhoto(SendPhoto sendPhoto);

    void producerSendPhotoDTO(PhotoToSendDTO photoToSendDTO);

    void producerSendDocumentDTO(DocumentToSendDTO documentToSendDTO);// <<< НОВИЙ МЕТОД

    void producerSendAudioDTO(AudioToSendDTO audioToSendDTO); // Новий метод

    // Альтернативно, один більш загальний метод, якщо хочете:
    // void producerSendBotApiMethod(org.telegram.telegrambots.meta.api.methods.BotApiMethod<?> method);
}