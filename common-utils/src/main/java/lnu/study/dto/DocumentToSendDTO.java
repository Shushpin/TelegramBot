package lnu.study.dto;

import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DocumentToSendDTO {
    private String chatId;
    private byte[] documentBytes;
    private String fileName;
    private String caption; // Опціонально
}