package lnu.study.dao;

import org.springframework.data.jpa.repository.JpaRepository;
import lnu.study.entity.AppDocument;

public interface AppDocumentDAO extends JpaRepository<AppDocument, Long> {
}