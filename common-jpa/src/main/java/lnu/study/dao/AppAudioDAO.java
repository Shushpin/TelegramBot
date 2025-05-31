package lnu.study.dao;

import lnu.study.entity.AppAudio;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppAudioDAO extends JpaRepository<AppAudio, Long> {
}