package lnu.study.dao;


import org.springframework.data.jpa.repository.JpaRepository;
import lnu.study.entity.BinaryContent;

public interface BinaryContentDAO extends JpaRepository<BinaryContent, Long> {
}
