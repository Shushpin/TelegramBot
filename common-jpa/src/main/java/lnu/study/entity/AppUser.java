package lnu.study.entity;

import jakarta.persistence.*;
import lnu.study.entity.enums.UserState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;


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
    public String firstname;
    public String lastname;
    public String username;
    public String email;
    private boolean isActive;
    @Enumerated(EnumType.STRING)
    private UserState state;


}
