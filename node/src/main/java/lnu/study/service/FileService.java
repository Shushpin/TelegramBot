package lnu.study.service;

import lnu.study.dto.ArchiveFileDetailDTO;
import lnu.study.entity.AppDocument;
import lnu.study.entity.AppPhoto;
import lnu.study.entity.AppUser; // <--- ДОДАНО ІМПОРТ
import lnu.study.service.enums.LinkType;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.io.IOException;
import java.util.List;

public interface FileService {

    AppDocument processDoc(Message telegramMessage);
    AppPhoto processPhoto(Message telegramMessage);
    String generateFileName(Long docId, LinkType linkType);
    String generateLink(Long docId, LinkType linkType);
    byte[] downloadFileAsByteArray(String fileId);

    // Новий метод для створення ZIP-архіву
    byte[] createZipArchiveFromTelegramFiles(List<ArchiveFileDetailDTO> filesToArchiveDetails) throws IOException;

    // Новий метод для збереження згенерованого архіву
    AppDocument saveGeneratedArchive(AppUser appUser, byte[] archiveBytes, String archiveFileName); // <--- ДОДАНО МЕТОД
}