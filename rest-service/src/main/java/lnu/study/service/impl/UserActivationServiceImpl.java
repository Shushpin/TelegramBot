package lnu.study.service.impl;

import lnu.study.dao.AppUserDAO;
import lnu.study.service.UserActivationService;
import lnu.study.utils.CryptoTool;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

@Log4j2
@Service
public class UserActivationServiceImpl implements UserActivationService {
    private final AppUserDAO appUserDAO;
    private final CryptoTool cryptoTool;

    public UserActivationServiceImpl(AppUserDAO appUserDAO, CryptoTool cryptoTool) {
        this.appUserDAO = appUserDAO;
        this.cryptoTool = cryptoTool;
    }
    @Override
    public boolean activation(String cryptoUserId) {
        var userId = cryptoTool.idOf(cryptoUserId);
        // ---> ВАЖЛИВА ЗМІНА: Додаємо перевірку на null перед викликом DAO <---
        if (userId == null) {
            log.warn("Invalid cryptoUserId '{}' resulted in null userId. Activation failed.", cryptoUserId);
            return false; // Явно повертаємо false, якщо ID не розшифрувався
        }
        var optionalAppUser = appUserDAO.findById(userId);
        if (optionalAppUser.isPresent()) {
            var user = optionalAppUser.get();
            user.setActive(true);
            appUserDAO.save(user);
            return true;
        }
        return false;
    }
}
