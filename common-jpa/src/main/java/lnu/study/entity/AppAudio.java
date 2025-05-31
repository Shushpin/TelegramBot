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
@Table(name = "app_audio") // Нова таблиця для аудіо
public class AppAudio {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String telegramFileId; // ID файлу в Telegram

    @OneToOne // Зв'язок з бінарним вмістом
    private BinaryContent binaryContent;

    private Long fileSize;       // Розмір файлу
    private String mimeType;     // MIME-тип (наприклад, "audio/ogg", "audio/mpeg")
    private Integer duration;     // Тривалість в секундах
    private String fileName;       // Ім'я файлу (для Audio є, для Voice генеруємо)

    // Можна додати зв'язок з AppUser, якщо потрібно відстежувати, хто завантажив
    // @ManyToOne
    // @JoinColumn(name = "app_user_id")
    // private AppUser appUser;
}