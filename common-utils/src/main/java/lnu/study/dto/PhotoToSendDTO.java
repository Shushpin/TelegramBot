package lnu.study.dto;

import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PhotoToSendDTO {
    private String chatId;
    private byte[] photoBytes;
    private String fileName;
    private String caption; // Опціонально, може бути null
}