package lnu.study.service.impl;

import lnu.study.dao.AppUserDAO;
import lnu.study.service.UserActivationService;
import lnu.study.utils.CryptoTool;
import org.springframework.stereotype.Service;

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
