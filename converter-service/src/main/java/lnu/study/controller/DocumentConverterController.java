package lnu.study.controller;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriUtils;
import java.nio.charset.StandardCharsets;

import java.io.*;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;

@RestController
@RequestMapping("/api/document")
public class DocumentConverterController {

    private static final Logger LOGGER = Logger.getLogger(DocumentConverterController.class.getName());

    @CrossOrigin(origins = "*")
    @PostMapping("/convert")
    public ResponseEntity<InputStreamResource> convertDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam("format") String format) {
        File inputFile = null;
        File outputDir = null;

        try {
            // Проверка поддерживаемых форматов
            if (!isSupportedFormat(format.toLowerCase())) { // Переводимо в нижній регістр для надійності
                LOGGER.log(Level.WARNING, "Unsupported target document format: {0}", format);
                return ResponseEntity.badRequest().body(null);
            }

            // Ограничение на размер файла (50 MB)
            if (file.getSize() > 50 * 1024 * 1024) {
                LOGGER.log(Level.WARNING, "File size exceeds the limit of 50 MB");
                return ResponseEntity.badRequest().body(null);
            }

            // Використовуємо оригінальне розширення файлу для тимчасового файлу, якщо можливо
            inputFile = File.createTempFile("input_document_", getExtension(file.getOriginalFilename()));
            file.transferTo(inputFile);

            outputDir = Files.createTempDirectory("document_conversion_").toFile();

            String convertParam = resolveConvertParam(format.toLowerCase());
            if (convertParam == null) {
                LOGGER.log(Level.WARNING, "No conversion parameter found for format: {0}", format);
                return ResponseEntity.badRequest().body(null);
            }

            LOGGER.log(Level.INFO, "Attempting to convert file {0} to {1} using soffice. Input: {2}, OutputDir: {3}",
                    new Object[]{file.getOriginalFilename(), format, inputFile.getAbsolutePath(), outputDir.getAbsolutePath()});


            ProcessBuilder processBuilder = new ProcessBuilder(
                    "soffice", "--headless", "--convert-to", convertParam,
                    "--outdir", outputDir.getAbsolutePath(),
                    inputFile.getAbsolutePath()
            );
            processBuilder.redirectErrorStream(true); // Об'єднуємо stdout та stderr
            Process process = processBuilder.start();

            // Читання виводу процесу для діагностики
            StringBuilder processOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOGGER.log(Level.INFO, "soffice: {0}", line);
                    processOutput.append(line).append(System.lineSeparator());
                }
            }

            int exitCode = process.waitFor();
            LOGGER.log(Level.INFO, "LibreOffice conversion process completed with exit code {0}", exitCode);

            if (exitCode != 0) {
                LOGGER.log(Level.SEVERE, "LibreOffice conversion failed with exit code {0}. Process output:\n{1}",
                        new Object[]{exitCode, processOutput.toString()});
                return ResponseEntity.status(500).body(null);
            }

            File convertedFile = findConvertedFile(outputDir, format.toLowerCase(), inputFile.getName());
            if (convertedFile == null || !convertedFile.exists()) {
                LOGGER.log(Level.SEVERE, "Converted file not found in output directory: {0}. Expected format: {1}. Process output:\n{2}",
                        new Object[]{outputDir.getAbsolutePath(), format, processOutput.toString()});
                return ResponseEntity.status(500).body(null);
            }
            LOGGER.log(Level.INFO, "Converted file found: {0}", convertedFile.getAbsolutePath());


            byte[] fileBytes = Files.readAllBytes(convertedFile.toPath());
            InputStreamResource resource = new InputStreamResource(new ByteArrayInputStream(fileBytes));

            String mimeType = resolveMimeType(format.toLowerCase());

            String originalFilename = file.getOriginalFilename();
            // Запобігання NullPointerException, якщо originalFilename не встановлено
            String baseName = removeExtension(originalFilename != null ? originalFilename : "file");
            String newFilename = baseName + "." + format.toLowerCase(); // Використовуємо вже нижній регістр

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(mimeType)); // Встановлюємо MIME-тип
            String encodedFilename = UriUtils.encode(newFilename, StandardCharsets.UTF_8);
            headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFilename);
            headers.setContentLength(fileBytes.length); // Встановлюємо розмір контенту

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(resource);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error during document conversion: " + e.getMessage(), e);
            return ResponseEntity.status(500).body(null);
        } finally {
            if (inputFile != null && inputFile.exists()) {
                boolean deleted = inputFile.delete();
                if (!deleted) {
                    LOGGER.log(Level.WARNING, "Could not delete temporary input file: {0}", inputFile.getAbsolutePath());
                }
            }
            if (outputDir != null && outputDir.exists()) {
                File[] filesInOutputDir = outputDir.listFiles();
                if (filesInOutputDir != null) {
                    for (File f : filesInOutputDir) {
                        boolean deleted = f.delete();
                        if (!deleted) {
                            LOGGER.log(Level.WARNING, "Could not delete temporary file in output dir: {0}", f.getAbsolutePath());
                        }
                    }
                }
                boolean deleted = outputDir.delete();
                if (!deleted) {
                    LOGGER.log(Level.WARNING, "Could not delete temporary output directory: {0}", outputDir.getAbsolutePath());
                }
            }
        }
    }

    private boolean isSupportedFormat(String format) {
        // format вже буде в нижньому регістрі
        return format.equals("pdf") ||
                format.equals("docx") ||
                format.equals("odt") ||
                format.equals("txt");
    }

    private String resolveMimeType(String format) {
        // format вже буде в нижньому регістрі
        switch (format) {
            case "pdf":
                return "application/pdf";
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "odt":
                return "application/vnd.oasis.opendocument.text";
            case "txt":
                return "text/plain";
            default:
                return "application/octet-stream";
        }
    }

    private String getExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return ".tmp";
        }
        int lastIndex = filename.lastIndexOf(".");
        if (lastIndex == -1 || lastIndex == filename.length() - 1) { // Немає розширення або крапка в кінці
            return ".tmp"; // Або повертати порожній рядок, якщо LibreOffice краще впорається без нього
        }
        return filename.substring(lastIndex);
    }

    private String removeExtension(String filename) {
        if (filename == null) return "file"; // Повертаємо ім'я за замовчуванням, якщо filename null
        int lastDot = filename.lastIndexOf('.');
        if (lastDot == -1) {
            return filename;
        }
        return filename.substring(0, lastDot);
    }

    private String resolveConvertParam(String format) {
        // format вже буде в нижньому регістрі
        switch (format) {
            case "pdf":
                return "pdf:writer_pdf_Export";
            case "docx":
                return "docx"; // LibreOffice сам обере відповідний фільтр, наприклад "MS Word 2007 XML"
            case "odt":
                return "odt";
            case "txt":
                return "txt:Text"; // Стандартний текстовий експорт.
            // Для UTF-8 можна спробувати "txt:TextEncodings:UTF8", якщо будуть проблеми.
            default:
                return null;
        }
    }

    private File findConvertedFile(File outputDir, String targetFormat, String originalInputName) {
        // LibreOffice зберігає конвертований файл з тим же іменем, що й вхідний, але з новим розширенням.
        // Видаляємо розширення з originalInputName, щоб отримати базове ім'я.
        String baseInputName = removeExtension(originalInputName);
        File expectedFile = new File(outputDir, baseInputName + "." + targetFormat);
        if (expectedFile.exists()) {
            return expectedFile;
        }
        // Резервний варіант: якщо файл один, і він має потрібне розширення
        // (це може трапитися, якщо originalInputName не мав розширення, або ім'я файлу було змінено)
        File[] files = outputDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().toLowerCase().endsWith("." + targetFormat)) {
                    return f;
                }
            }
        }
        return null;
    }
}