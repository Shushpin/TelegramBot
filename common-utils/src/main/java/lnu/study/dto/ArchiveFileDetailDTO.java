package lnu.study.dto;

import java.io.Serializable;

public class ArchiveFileDetailDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String telegramFileId;
    private String originalFileName;
    private String telegramFileType; // "document", "photo", "video", "audio"

    public ArchiveFileDetailDTO(String telegramFileId, String originalFileName, String telegramFileType) {
        this.telegramFileId = telegramFileId;
        this.originalFileName = originalFileName;
        this.telegramFileType = telegramFileType;
    }

    public String getTelegramFileId() {
        return telegramFileId;
    }

    public void setTelegramFileId(String telegramFileId) {
        this.telegramFileId = telegramFileId;
    }

    public String getOriginalFileName() {
        // Логіка для генерації імені, якщо воно відсутнє, може бути тут або при використанні DTO
        if (originalFileName == null || originalFileName.trim().isEmpty()) {
            if ("photo".equals(telegramFileType)) {
                return "photo_" + (telegramFileId != null ? telegramFileId.substring(0, Math.min(telegramFileId.length(), 10)) : "unknown") + ".jpg";
            } else if ("video".equals(telegramFileType)) {
                return "video_" + (telegramFileId != null ? telegramFileId.substring(0, Math.min(telegramFileId.length(), 10)) : "unknown") + ".mp4";
            } else if ("audio".equals(telegramFileType)) {
                return "audio_" + (telegramFileId != null ? telegramFileId.substring(0, Math.min(telegramFileId.length(), 10)) : "unknown") + ".mp3";
            }
            return "file_" + (telegramFileId != null ? telegramFileId.substring(0, Math.min(telegramFileId.length(), 10)) : "unknown");
        }
        return originalFileName;
    }

    public void setOriginalFileName(String originalFileName) {
        this.originalFileName = originalFileName;
    }

    public String getTelegramFileType() {
        return telegramFileType;
    }

    public void setTelegramFileType(String telegramFileType) {
        this.telegramFileType = telegramFileType;
    }

    @Override
    public String toString() {
        return "ArchiveFileDetail{" +
                "telegramFileId='" + telegramFileId + '\'' +
                ", originalFileName='" + originalFileName + '\'' +
                ", telegramFileType='" + telegramFileType + '\'' +
                '}';
    }
}