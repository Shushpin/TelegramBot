package lnu.study.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor // Необхідно для десеріалізації Jackson в RabbitMQ consumer
@AllArgsConstructor // Необхідно для @Builder
public class AudioToSendDTO {
    private String chatId;
    private byte[] audioBytes;
    private String fileName;
    private String caption; // Опціонально, наприклад, "Сконвертовано: original_name.mp3"

}