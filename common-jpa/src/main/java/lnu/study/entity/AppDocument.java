package lnu.study.entity;

import lombok.*;
import jakarta.persistence.*;

@Getter
@Setter
@EqualsAndHashCode(exclude = {"id", "binaryContent", "appUser"}) // Додав виключення для полів зв'язку
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"binaryContent", "appUser"}) // Додав ToString, виключивши бінарний вміст та користувача для коротших логів
@Entity
@Table(name = "app_document")
public class AppDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String telegramFileId;
    private String docName;

    @OneToOne(fetch = FetchType.LAZY) // Рекомендовано додати FetchType.LAZY
    private BinaryContent binaryContent;

    private String mimeType;
    private Long fileSize;

    @ManyToOne // Зв'язок "багато документів до одного користувача"
    @JoinColumn(name = "app_user_id") // Назва стовпця в таблиці app_document для зовнішнього ключа
    private AppUser appUser; // <--- ДОДАНО ПОЛЕ ДЛЯ ВЛАСНИКА

    // Метод orElse(Object o) я прибрав, оскільки він не стандартний.
    // Якщо він тобі потрібен для чогось іншого, можеш повернути.
}