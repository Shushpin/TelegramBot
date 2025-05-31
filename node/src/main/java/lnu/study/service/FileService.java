package lnu.study.service;

import lnu.study.dto.ArchiveFileDetailDTO;
import lnu.study.entity.AppDocument;
import lnu.study.entity.AppPhoto;
import lnu.study.entity.AppUser;
import lnu.study.service.enums.LinkType;
import org.telegram.telegrambots.meta.api.objects.Message;
import lnu.study.entity.AppAudio;

import java.io.IOException;
import java.util.List;

public interface FileService {

    AppDocument processDoc(Message telegramMessage);
    AppPhoto processPhoto(Message telegramMessage);
    AppAudio processAudio(Message telegramMessage); // <--- ДОДАНО НОВИЙ МЕТОД
    String generateFileName(Long docId, LinkType linkType);
    String generateLink(Long fileId, LinkType linkType);
    byte[] downloadFileAsByteArray(String fileId);

    // Новий метод для створення ZIP-архіву
    byte[] createZipArchiveFromTelegramFiles(List<ArchiveFileDetailDTO> filesToArchiveDetails) throws IOException;

    // Новий метод для збереження згенерованого архіву
    AppDocument saveGeneratedArchive(AppUser appUser, byte[] archiveBytes, String archiveFileName); // <--- ДОДАНО МЕТОД
}