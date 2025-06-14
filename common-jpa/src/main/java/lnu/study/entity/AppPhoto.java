package lnu.study.entity;

import lombok.*;

import jakarta.persistence.*;

@Getter
@Setter
@EqualsAndHashCode(exclude = "id")
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "app_photo")
public class AppPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String telegramFileId;


    @OneToOne
    private BinaryContent binaryContent;
    private Integer fileSize;
}
