package lnu.study.service;

import lnu.study.entity.AppPhoto;
import org.telegram.telegrambots.meta.api.objects.Message;
import lnu.study.entity.AppDocument;

public interface FileService {

    AppDocument processDoc(Message telegramMessage);
    AppPhoto processPhoto(Message telegramMessage);

}
