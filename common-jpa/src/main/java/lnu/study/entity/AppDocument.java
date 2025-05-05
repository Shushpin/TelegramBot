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
@Table(name = "app_document")
public class AppDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String telegramFileId;
    private String docName;

    @OneToOne
    private BinaryContent binaryContent;
    private String mimeType;
    private Long fileSize;

    public AppDocument orElse(Object o) {
        return this;
    }
}
