package lnu.study.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoToSendDTO {
    private String chatId;
    private byte[] videoBytes;
    private String fileName;
    private String caption; // Опціонально
    private Integer duration; // Опціонально, в секундах
    private Integer width;    // Опціонально
    private Integer height;   // Опціонально
    // private Boolean supportsStreaming; // Опціонально
}