package lnu.study.service;

import lnu.study.dto.AudioToSendDTO;
import lnu.study.dto.DocumentToSendDTO;
import lnu.study.dto.PhotoToSendDTO;
import lnu.study.dto.VideoToSendDTO;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;

public interface ProducerService {

    void producerAnswer(SendMessage sendMessage);

    void producerSendDocument(SendDocument sendDocument);

    void producerSendPhoto(SendPhoto sendPhoto);

    void producerSendPhotoDTO(PhotoToSendDTO photoToSendDTO);

    void producerSendDocumentDTO(DocumentToSendDTO documentToSendDTO);

    void producerSendAudioDTO(AudioToSendDTO audioToSendDTO);

    void producerSendVideoDTO(VideoToSendDTO videoToSendDTO);

    void producerAnswerCallbackQuery(String callbackQueryId, String text);


}