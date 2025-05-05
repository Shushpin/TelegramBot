package lnu.study.dao;

import lnu.study.entity.AppPhoto;
import org.springframework.data.jpa.repository.JpaRepository;


public interface AppPhotoDAO extends JpaRepository<AppPhoto, Long> {
}