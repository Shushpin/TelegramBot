package lnu.study.dao;

import lnu.study.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserDAO extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByTelegramUserId(Long Id);
    Optional<AppUser> findById(Long Id);
    Optional<AppUser> findByEmail(String Id);
}
