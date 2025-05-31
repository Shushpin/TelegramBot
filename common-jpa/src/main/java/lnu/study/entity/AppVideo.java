package lnu.study.entity;

import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@EqualsAndHashCode(exclude = "id")
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "app_video") // Нова таблиця для відео
public class AppVideo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String telegramFileId; // ID файлу в Telegram

    @OneToOne // Зв'язок з бінарним вмістом
    private BinaryContent binaryContent;

    private Long fileSize;       // Розмір файлу
    private String mimeType;     // MIME-тип (наприклад, "video/mp4")
    private Integer duration;     // Тривалість в секундах
    private Integer width;        // Ширина
    private Integer height;       // Висота
    private String fileName;       // Ім'я файлу (може бути відсутнім, тоді генеруємо)

    // Можна додати зв'язок з AppUser
    // @ManyToOne
    // @JoinColumn(name = "app_user_id")
    // private AppUser appUser;
}