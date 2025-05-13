package lnu.study.service;

import lnu.study.entity.AppPhoto;
import lnu.study.service.enums.LinkType;
import org.telegram.telegrambots.meta.api.objects.Message;
import lnu.study.entity.AppDocument;

public interface FileService {

    AppDocument processDoc(Message telegramMessage);
    AppPhoto processPhoto(Message telegramMessage);
    String generateFileName(Long docId, LinkType linkType);

    String generateLink(Long docId, LinkType linkType);

    byte[] downloadFileAsByteArray(String fileId);
}
