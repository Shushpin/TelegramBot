package lnu.study.dao;

import lnu.study.entity.AppVideo;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppVideoDAO extends JpaRepository<AppVideo, Long> {
}