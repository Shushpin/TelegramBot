package lnu.study.controller;

import jakarta.servlet.http.HttpServletResponse;
import lnu.study.service.FileService;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;


import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Log4j2
@RequestMapping("/file")
@RestController
public class FileController {
    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @RequestMapping(method = RequestMethod.GET, value = "get-doc")
    public void getDoc(@RequestParam("id") String id, HttpServletResponse response) {
        //TODO для форматування бедРеквест додати КонтроллерЕдвас
        var doc = fileService.getDocument(id);
        if (doc == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setContentType(MediaType.parseMediaType(doc.getMimeType()).toString());
        response.setHeader("Content-Disposition","attachment; filename=" + doc.getDocName());
        response.setStatus(HttpServletResponse.SC_OK);

        var binaryContent = doc.getBinaryContent();

        try{
            var out = response.getOutputStream();
            out.write(binaryContent.getFileAsArrayOfBytes());
            out.close();
        } catch (IOException e) {
            log.error(e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(method = RequestMethod.GET, value = "get-photo")
    public void getPhoto(@RequestParam("id") String id, HttpServletResponse response) {
        //TODO для форматування бедРеквест додати КонтроллерЕдвас
        var photo = fileService.getPhoto(id);
        if (photo == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setContentType(MediaType.IMAGE_JPEG.toString());
        response.setHeader("Content-Disposition","attachment;");
        response.setStatus(HttpServletResponse.SC_OK);

        var binaryContent = photo.getBinaryContent();

        try{
            var out = response.getOutputStream();
            out.write(binaryContent.getFileAsArrayOfBytes());
            out.close();
        } catch (IOException e) {
            log.error(e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }

    }
    @RequestMapping(method = RequestMethod.GET, value = "get-audio")
    public void getAudio(@RequestParam("id") String id, HttpServletResponse response) {
        //TODO для форматування бедРеквест додати КонтроллерЕдвас (якщо потрібно)
        var audio = fileService.getAudio(id); // Використовуємо метод з FileService (rest-service)
        if (audio == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // Встановлюємо MIME-тип з AppAudio, якщо він є, інакше - стандартний
        String contentType = audio.getMimeType() != null && !audio.getMimeType().isBlank()
                ? audio.getMimeType()
                : "application/octet-stream"; // Загальний тип для невідомих даних
        try {
            response.setContentType(MediaType.parseMediaType(contentType).toString());
        } catch (Exception e) {
            log.warn("Не вдалося розпарсити MIME-тип '{}', встановлюю application/octet-stream", contentType, e);
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        }

        // Встановлюємо заголовок для завантаження файлу з його іменем
        String fileName = audio.getFileName() != null && !audio.getFileName().isBlank()
                ? audio.getFileName()
                : "audio_file"; // Запасне ім'я файлу
        // Кодуємо ім'я файлу для правильного відображення не-латинських символів
        String encodedFileName = UriUtils.encode(fileName, StandardCharsets.UTF_8);
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encodedFileName);

        response.setStatus(HttpServletResponse.SC_OK);

        var binaryContent = audio.getBinaryContent();
        if (binaryContent == null || binaryContent.getFileAsArrayOfBytes() == null) {
            log.error("BinaryContent або його масив байтів є null для аудіо id: {}", id);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        try {
            var out = response.getOutputStream();
            out.write(binaryContent.getFileAsArrayOfBytes());
            out.close();
        } catch (IOException e) {
            log.error("Помилка запису аудіо у вихідний потік для id {}: {}", id, e.getMessage(), e);
            // Статус вже може бути встановлений, але це для логування
            if (!response.isCommitted()) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }
    }
    @RequestMapping(method = RequestMethod.GET, value = "get-video")
    public void getVideo(@RequestParam("id") String id, HttpServletResponse response) {
        var video = fileService.getVideo(id);
        if (video == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String contentType = video.getMimeType() != null && !video.getMimeType().isBlank()
                ? video.getMimeType()
                : "video/mp4"; // За замовчуванням для відео можна mp4
        try {
            response.setContentType(MediaType.parseMediaType(contentType).toString());
        } catch (Exception e) {
            log.warn("Не вдалося розпарсити MIME-тип '{}' для відео, встановлюю video/mp4", contentType, e);
            response.setContentType("video/mp4");
        }

        String fileName = video.getFileName() != null && !video.getFileName().isBlank()
                ? video.getFileName()
                : "video_file.mp4";
        String encodedFileName = UriUtils.encode(fileName, StandardCharsets.UTF_8);
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encodedFileName);

        response.setStatus(HttpServletResponse.SC_OK);

        var binaryContent = video.getBinaryContent();
        if (binaryContent == null || binaryContent.getFileAsArrayOfBytes() == null) {
            log.error("BinaryContent або його масив байтів є null для відео id: {}", id);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        try {
            var out = response.getOutputStream();
            out.write(binaryContent.getFileAsArrayOfBytes());
            out.close();
        } catch (IOException e) {
            log.error("Помилка запису відео у вихідний потік для id {}: {}", id, e.getMessage(), e);
            if (!response.isCommitted()) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }
    }
}
