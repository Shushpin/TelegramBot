package lnu.study.controller;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;

@RestController
@RequestMapping("/api/audio")
public class AudioConverterController {

    private static final Logger LOGGER = Logger.getLogger(AudioConverterController.class.getName());

    @CrossOrigin(origins = "*") // Если необходимо принимать запросы с другого домена/порта
    @PostMapping("/convert")
    public ResponseEntity<InputStreamResource> convertAudio(
            @RequestParam("file") MultipartFile file,
            @RequestParam("format") String format) {
        File inputFile = null;
        File outputFile = null;

        try {
            // Проверка поддерживаемых форматов
            if (!isSupportedFormat(format)) {
                LOGGER.log(Level.WARNING, "Unsupported target audio format: {0}", format);
                return ResponseEntity.badRequest().body(null);
            }

            // Ограничение на размер файла (50 MB)
            if (file.getSize() > 50 * 1024 * 1024) {
                LOGGER.log(Level.WARNING, "File size exceeds the limit of 50 MB");
                return ResponseEntity.badRequest().body(null);
            }

            // Сохранение исходного файла во временную директорию
            inputFile = File.createTempFile("input_audio", getExtension(file.getOriginalFilename()));
            file.transferTo(inputFile);

            // Создание выходного файла с нужным расширением
            outputFile = File.createTempFile("output_audio", "." + format.toLowerCase());

            // Вызов FFmpeg для конвертации аудио
            // Пример: ffmpeg -y -i input.mp3 output.wav
            // ffmpeg самостоятельно определит входной формат и сгенерирует выходной
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "ffmpeg", "-y",      // -y для перезаписи без запроса
                    "-i", inputFile.getAbsolutePath(),
                    outputFile.getAbsolutePath()
            );
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            // Логирование вывода FFmpeg
            long startTime = System.currentTimeMillis();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOGGER.log(Level.INFO, line);
                }
            }

            int exitCode = process.waitFor();
            long endTime = System.currentTimeMillis();
            LOGGER.log(Level.INFO, "FFmpeg audio process completed in {0} ms", (endTime - startTime));

            if (exitCode != 0) {
                LOGGER.log(Level.SEVERE, "FFmpeg audio process failed with exit code {0}", exitCode);
                return ResponseEntity.status(500).body(null);
            }

            // Читаем выходной файл в память
            byte[] fileBytes = Files.readAllBytes(outputFile.toPath());
            InputStreamResource resource = new InputStreamResource(new ByteArrayInputStream(fileBytes));

            // Определяем MIME-тип для аудио
            String mimeType = resolveMimeType(format);

            // Получаем оригинальное имя файла без расширения
            String originalFilename = file.getOriginalFilename();
            String baseName = removeExtension(originalFilename);
            String newFilename = baseName + "-converted." + format;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(mimeType));
            headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + newFilename + "\"");

            return ResponseEntity.ok()
                    .headers(headers)
                    .contentLength(fileBytes.length)
                    .body(resource);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error during audio conversion: {0}", e.getMessage());
            return ResponseEntity.status(500).body(null);
        } finally {
            // Удаляем временные файлы после чтения
            if (inputFile != null && inputFile.exists()) inputFile.delete();
            if (outputFile != null && outputFile.exists()) outputFile.delete();
        }
    }

    private boolean isSupportedFormat(String format) {
        // Добавляем поддержку различных аудиоформатов
        return format.equalsIgnoreCase("mp3") ||
                format.equalsIgnoreCase("wav") ||
                format.equalsIgnoreCase("aac") ||
                format.equalsIgnoreCase("flac") ||
                format.equalsIgnoreCase("ogg");
    }

    private String resolveMimeType(String format) {
        switch (format.toLowerCase()) {
            case "mp3":
                return "audio/mpeg";
            case "wav":
                return "audio/wav";
            case "aac":
                return "audio/aac";
            case "flac":
                return "audio/flac";
            case "ogg":
                return "audio/ogg";
            default:
                return "application/octet-stream";
        }
    }

    private String getExtension(String filename) {
        if (filename == null) return ".tmp";
        int lastIndex = filename.lastIndexOf(".");
        if (lastIndex == -1) {
            return ".tmp"; // Если расширения нет
        }
        return filename.substring(lastIndex);
    }

    private String removeExtension(String filename) {
        if (filename == null) return "converted-file";
        int lastDot = filename.lastIndexOf('.');
        if (lastDot == -1) {
            return filename; // Нет расширения
        }
        return filename.substring(0, lastDot);
    }
}
