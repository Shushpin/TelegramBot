package lnu.study.entity;

import jakarta.persistence.*;
import lnu.study.entity.enums.UserState;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Getter
@Setter
@EqualsAndHashCode(exclude = "id")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "app_user")
public class AppUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long telegramUserId;
    @CreationTimestamp
    private LocalDateTime firstLoginDateTime;
    public String firstName;
    public String lastName;
    public String userName;
    public String email;
    private boolean isActive;
    @Enumerated(EnumType.STRING)
    private UserState state;
    private String pendingFileId;
    private String pendingOriginalFileName;

}
