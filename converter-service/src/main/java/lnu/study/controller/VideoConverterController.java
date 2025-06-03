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
@RequestMapping("/api/video")
public class VideoConverterController {

    private static final Logger LOGGER = Logger.getLogger(VideoConverterController.class.getName());

    @CrossOrigin(origins = "*")
    @PostMapping("/convert")
    public ResponseEntity<InputStreamResource> convertVideo(
            @RequestParam("file") MultipartFile file,
            @RequestParam("format") String format) {
        File inputFile = null;
        File outputFile = null;

        try {
            if (!isSupportedFormat(format)) {
                LOGGER.log(Level.WARNING, "Unsupported target video format: {0}", format);
                return ResponseEntity.badRequest().body(null);
            }

            if (file.getSize() > 75 * 1024 * 1024) {
                LOGGER.log(Level.WARNING, "File size exceeds the limit of 75 MB");
                return ResponseEntity.badRequest().body(null);
            }

            inputFile = File.createTempFile("input_video", getExtension(file.getOriginalFilename()));
            file.transferTo(inputFile);

            outputFile = File.createTempFile("output_video", "." + format.toLowerCase());


            ProcessBuilder processBuilder = new ProcessBuilder(
                    "ffmpeg", "-y",
                    "-i", inputFile.getAbsolutePath(),
                    outputFile.getAbsolutePath()
            );
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            long startTime = System.currentTimeMillis();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOGGER.log(Level.INFO, line);
                }
            }

            int exitCode = process.waitFor();
            long endTime = System.currentTimeMillis();
            LOGGER.log(Level.INFO, "FFmpeg video process completed in {0} ms", (endTime - startTime));

            if (exitCode != 0) {
                LOGGER.log(Level.SEVERE, "FFmpeg video process failed with exit code {0}", exitCode);
                return ResponseEntity.status(500).body(null);
            }

            byte[] fileBytes = Files.readAllBytes(outputFile.toPath());
            InputStreamResource resource = new InputStreamResource(new ByteArrayInputStream(fileBytes));

            String mimeType = resolveMimeType(format);

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
            LOGGER.log(Level.SEVERE, "Error during video conversion: {0}", e.getMessage());
            return ResponseEntity.status(500).body(null);
        } finally {
            if (inputFile != null && inputFile.exists()) inputFile.delete();
            if (outputFile != null && outputFile.exists()) outputFile.delete();
        }
    }

    private boolean isSupportedFormat(String format) {
        return format.equalsIgnoreCase("mp4") ||
                format.equalsIgnoreCase("mkv") ||
                format.equalsIgnoreCase("mov") ||
                format.equalsIgnoreCase("webm");
    }

    private String resolveMimeType(String format) {
        switch (format.toLowerCase()) {
            case "mp4":
                return "video/mp4";
            case "mkv":
                return "video/x-matroska";
            case "mov":
                return "video/quicktime";
            case "webm":
                return "video/webm";
            default:
                return "application/octet-stream";
        }
    }

    private String getExtension(String filename) {
        if (filename == null) return ".tmp";
        int lastIndex = filename.lastIndexOf(".");
        if (lastIndex == -1) {
            return ".tmp";
        }
        return filename.substring(lastIndex);
    }

    private String removeExtension(String filename) {
        if (filename == null) return "converted-file";
        int lastDot = filename.lastIndexOf('.');
        if (lastDot == -1) {
            return filename;
        }
        return filename.substring(0, lastDot);
    }
}

