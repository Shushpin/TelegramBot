package lnu.study.service;

import lnu.study.entity.AppUser;
import org.springframework.transaction.annotation.Transactional;

public interface AppUserService {
    String registerUser(AppUser appUser);
    String setEmail(AppUser appUser, String email);
    String resendActivationEmail(AppUser appUser);

    @Transactional
    void activateUser(String cryptoUserId);
}
