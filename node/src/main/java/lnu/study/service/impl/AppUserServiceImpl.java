package lnu.study.service.impl;

import lnu.study.dao.AppUserDAO;
import lnu.study.dto.MailParams;
import lnu.study.entity.AppUser;
import lnu.study.entity.enums.UserState;
import lnu.study.service.AppUserService;
import lnu.study.utils.CryptoTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;

@Log4j2
@Service
@RequiredArgsConstructor
public class AppUserServiceImpl implements AppUserService {
    private final AppUserDAO appUserDAO;
    private final CryptoTool cryptoTool;

    @Value("${service.mail.uri}")
    private String mailServiceUri;

    @Override
    @Transactional
    public String registerUser(AppUser appUser) {
        if (appUser == null) {
            log.error("ENTERING registerUser: AppUser object is NULL!");
            return "Помилка реєстрації: дані користувача відсутні.";
        }

        log.info("ENTERING registerUser: AppUser ID={}, TelegramID={}, CurrentState='{}', IsActive={}",
                appUser.getId(),
                appUser.getTelegramUserId(),
                (appUser.getState() != null ? appUser.getState().name() : "null_state_object"),
                appUser.isActive());

        if (Boolean.TRUE.equals(appUser.isActive())) {
            log.warn("User with TelegramID {} is already registered and active. CurrentState='{}'",
                    appUser.getTelegramUserId(),
                    (appUser.getState() != null ? appUser.getState().name() : "null_state_object"));
            return "Ви вже зареєстровані та активні!";
        }

        // Якщо користувач вже має email і знаходиться в стані очікування активації,
        // можливо, варто нагадати йому перевірити пошту або запропонувати повторно відправити лист.
        // Ця логіка залишилася незмінною.
        if (appUser.getEmail() != null && UserState.WAIT_FOR_EMAIL_STATE.equals(appUser.getState())) {
            log.warn("User with TelegramID {} has email {} and is already in WAIT_FOR_EMAIL_STATE. Resending activation email.",
                    appUser.getTelegramUserId(), appUser.getEmail());
            // Ініціюємо повторну відправку листа
            var cryptoUserId = cryptoTool.hashOf(appUser.getId()); // Потрібен ID збереженого користувача
            ResponseEntity<String> response = sendEmailForActivation(appUser.getEmail(), cryptoUserId);
            if (response.getStatusCode() == HttpStatus.OK) {
                return "Вам на пошту (" + appUser.getEmail() + ") повторно відправлено листа. Перейдіть за посиланням для підтвердження.";
            } else {
                return "Ваш email (" + appUser.getEmail() + ") збережено, але не вдалося повторно відправити лист активації. Спробуйте пізніше.";
            }
        }

        // Якщо користувач ще не надав email або його стан не WAIT_FOR_EMAIL_STATE
        // (наприклад, він тільки-но ввів /registration або повернувся з іншого стану)
        // Ми встановлюємо його стан в WAIT_FOR_EMAIL_STATE, щоб MainServiceImpl
        // очікував на введення email.
        // Лист буде відправлено після того, як користувач надасть email і буде викликаний метод setEmail.
        appUser.setState(UserState.WAIT_FOR_EMAIL_STATE);
        log.info("User with TelegramID {}. Setting state to WAIT_FOR_EMAIL_STATE to await email input.", appUser.getTelegramUserId());
        // На цьому етапі ми НЕ відправляємо лист, оскільки email ще не відомий або не підтверджений для цього потоку реєстрації.

        log.info("BEFORE SAVE (registerUser): AppUser ID={}, TelegramID={}, StateToSave='{}', Email={}, IsActive={}",
                appUser.getId(),
                appUser.getTelegramUserId(),
                (appUser.getState() != null ? appUser.getState().name() : "null_state_object"),
                appUser.getEmail(), // email може бути null на цьому етапі
                appUser.isActive());

        AppUser savedAppUser;
        try {
            savedAppUser = appUserDAO.save(appUser);
            log.info("SUCCESS SAVE (registerUser): AppUser ID={}, TelegramID={}, SavedState='{}', Email='{}'",
                    savedAppUser.getId(), savedAppUser.getTelegramUserId(),
                    (savedAppUser.getState() != null ? savedAppUser.getState().name() : "null_state_object"),
                    savedAppUser.getEmail());
        } catch (Exception e) {
            log.error("!!!!!!!! EXCEPTION during save in registerUser for user with TelegramID {}: {}. Full stack trace:",
                    appUser.getTelegramUserId(), e.getMessage(), e);
            // Можливо, варто повернути більш інформативне повідомлення користувачу або кинути специфічне виключення
            return "Сталася помилка під час збереження даних. Спробуйте пізніше.";
        }

        // Після збереження користувача зі станом WAIT_FOR_EMAIL_STATE,
        // повідомляємо користувачу, що потрібно надіслати email.
        // Фактична відправка листа відбудеться в методі setEmail.
        return "Реєстрація майже завершена! Будь ласка, надішліть свою електронну адресу для активації облікового запису.";
    }

    @Override
    @Transactional
    public String setEmail(AppUser appUser, String email) {
        if (appUser == null) {
            log.error("ENTERING setEmail: AppUser object is NULL! Cannot set email.");
            return "Помилка: не вдалося знайти дані користувача.";
        }
        log.info("ENTERING setEmail for user with TelegramID {}. CurrentState='{}'. Email to set: {}",
                appUser.getTelegramUserId(),
                (appUser.getState() != null ? appUser.getState().name() : "null_state_object"),
                email);

        try {
            InternetAddress emailAddr = new InternetAddress(email);
            emailAddr.validate();
        } catch (AddressException e) {
            log.warn("Invalid email format provided by user {}: {}", appUser.getTelegramUserId(), email);
            return "Некоректний формат електронної пошти.";
        }

        appUser.setEmail(email);
        appUser.setState(UserState.WAIT_FOR_EMAIL_STATE);
        log.info("State for user with TelegramID {} set to WAIT_FOR_EMAIL_STATE. Email set to: {}", appUser.getTelegramUserId(), appUser.getEmail());

        log.info("BEFORE SAVE (setEmail): AppUser ID={}, TelegramID={}, StateToSave='{}', StateToSave.length={}, Email={}, IsActive={}",
                appUser.getId(),
                appUser.getTelegramUserId(),
                (appUser.getState() != null ? appUser.getState().name() : "null_state_object"),
                (appUser.getState() != null ? appUser.getState().name().length() : "N/A"),
                appUser.getEmail(),
                appUser.isActive());

        AppUser savedAppUser;
        try {
            savedAppUser = appUserDAO.save(appUser);
            log.info("SUCCESS SAVE (setEmail): AppUser ID={}, TelegramID={}, SavedState='{}', Email='{}'",
                    savedAppUser.getId(), savedAppUser.getTelegramUserId(),
                    (savedAppUser.getState() != null ? savedAppUser.getState().name() : "null_state_object"),
                    savedAppUser.getEmail());
        } catch (Exception e) {
            log.error("!!!!!!!! EXCEPTION during save in setEmail for user with TelegramID {}: {}. Full stack trace:",
                    appUser.getTelegramUserId(), e.getMessage(), e);
            throw e;
        }

        if (savedAppUser != null && UserState.WAIT_FOR_EMAIL_STATE.equals(savedAppUser.getState()) && savedAppUser.getEmail() != null) {
            var cryptoUserId = cryptoTool.hashOf(savedAppUser.getId());
            ResponseEntity<String> response = sendEmailForActivation(savedAppUser.getEmail(), cryptoUserId);

            if (response.getStatusCode() == HttpStatus.OK) {
                return "Вам на пошту (" + savedAppUser.getEmail() + ") відправлено листа. "
                        + "Перейдіть за посиланням в листі для підтвердження реєстрації.";
            } else {
                log.error("Failed to send activation email to {} for user {}. Mail service response: {}",
                        savedAppUser.getEmail(), savedAppUser.getTelegramUserId(), response.getBody());
                return "Ваш email (" + savedAppUser.getEmail() + ") збережено, але не вдалося відправити лист активації. " +
                        "Спробуйте пізніше команду /resend_email або зверніться до підтримки.";
            }
        } else {
            log.error("User {} saved in setEmail, but state is not WAIT_FOR_EMAIL_STATE or email is null. Actual state: {}, Email: {}",
                    (savedAppUser != null ? savedAppUser.getTelegramUserId() : "unknown"),
                    (savedAppUser != null && savedAppUser.getState() != null ? savedAppUser.getState().name() : "null_state"),
                    (savedAppUser != null ? savedAppUser.getEmail() : "null_email"));
            return "Ваш email оброблено, але сталася помилка зі встановленням статусу для відправки листа.";
        }
    }

    private ResponseEntity<String> sendEmailForActivation(String email, String cryptoUserId) {
        var restTemplate = new RestTemplate();
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var mailParams = MailParams.builder()
                .id(cryptoUserId)
                .emailTo(email)
                .build();
        var request = new HttpEntity<>(mailParams, headers);

        String targetUri = mailServiceUri + "/mail/send"; // <<<=== ЗМІНА ТУТ: Додаємо повний шлях

        log.info("Sending activation email to {} with cryptoUserId {}. URI: {}", email, cryptoUserId, targetUri);
        try {
            return restTemplate.exchange(targetUri, // <<<=== ЗМІНА ТУТ: Використовуємо повний шлях
                    HttpMethod.POST,
                    request,
                    String.class);
        } catch (Exception e) {
            log.error("Error calling mail service for email {}: {}", email, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Mail service call failed: " + e.getMessage());
        }
    }

    @Transactional
    @Override
    public void activateUser(String cryptoUserId) {
        var userId = cryptoTool.idOf(cryptoUserId);
        if (userId == null) {
            log.warn("Invalid cryptoUserId received for activation: {}", cryptoUserId);
            return;
        }

        var optionalAppUser = appUserDAO.findById(userId);
        if (optionalAppUser.isPresent()) {
            AppUser appUser = optionalAppUser.get();
            log.info("Activating user: ID={}, TelegramID={}, CurrentState='{}', IsActive={}",
                    appUser.getId(), appUser.getTelegramUserId(),
                    (appUser.getState() != null ? appUser.getState().name() : "null_state_object"),
                    appUser.isActive());

            if (UserState.WAIT_FOR_EMAIL_STATE.equals(appUser.getState())) {
                appUser.setActive(true);
                appUser.setState(UserState.EMAIL_CONFIRMED_STATE);
                log.info("User activated successfully. New state: EMAIL_CONFIRMED_STATE, IsActive: true");

                log.info("BEFORE SAVE (activateUser): AppUser ID={}, TelegramID={}, StateToSave='{}', StateToSave.length={}, Email={}, IsActive={}",
                        appUser.getId(),
                        appUser.getTelegramUserId(),
                        (appUser.getState() != null ? appUser.getState().name() : "null_state_object"),
                        (appUser.getState() != null ? appUser.getState().name().length() : "N/A"),
                        appUser.getEmail(),
                        appUser.isActive());
                try {
                    appUserDAO.save(appUser);
                    log.info("SUCCESS SAVE (activateUser): AppUser ID={}, TelegramID={}", appUser.getId(), appUser.getTelegramUserId());
                } catch (Exception e) {
                    log.error("!!!!!!!! EXCEPTION during save in activateUser for user with ID {}: {}. Full stack trace:",
                            appUser.getId(), e.getMessage(), e);
                    throw e;
                }
            } else {
                log.warn("User {} activation attempt in unexpected state: {}. Expected WAIT_FOR_EMAIL_STATE. Current isActive: {}",
                        appUser.getTelegramUserId(),
                        (appUser.getState() != null ? appUser.getState().name() : "null_state_object"),
                        appUser.isActive());
            }
        } else {
            log.warn("User not found for activation with cryptoUserId (decoded ID: {}): {}", userId, cryptoUserId);
        }
    }
    // ProjectTelegramBot/node/src/main/java/lnu/study/service/impl/AppUserServiceImpl.java
// ... (після інших методів) ...

    @Override
    @Transactional(readOnly = true) // Ця операція не змінює стан користувача, лише читає дані і відправляє email
    public String resendActivationEmail(AppUser appUser) {
        if (appUser == null) {
            log.error("Cannot resend email for null AppUser.");
            return "Помилка: не вдалося знайти дані користувача.";
        }

        log.info("Processing /resend_email request for user {}", appUser.getTelegramUserId());

        // Перевіряємо чи користувач у правильному стані
        if (!UserState.WAIT_FOR_EMAIL_STATE.equals(appUser.getState())) {
            log.warn("User {} requested /resend_email but is not in WAIT_FOR_EMAIL_STATE. Current state: {}", appUser.getTelegramUserId(), appUser.getState());
            // Можна додати перевірку, чи користувач вже активний
            if(Boolean.TRUE.equals(appUser.isActive())) {
                return "Ваш обліковий запис вже активовано!";
            }
            return "Повторно надіслати лист можна тільки під час процесу реєстрації, після введення email.";
        }

        String email = appUser.getEmail();
        if (email == null || email.isBlank()) {
            log.error("User {} is in WAIT_FOR_EMAIL_STATE but has no email saved. Cannot resend.", appUser.getTelegramUserId());
            // Стан WAIT_FOR_EMAIL_STATE без email не мав би виникати з поточною логікою, але перевірка не завадить
            return "Ваш email не знайдено в системі. Будь ласка, спочатку надішліть свій email.";
        }

        log.info("Attempting to resend activation email to {} for user {}", email, appUser.getTelegramUserId());
        var cryptoUserId = cryptoTool.hashOf(appUser.getId());
        ResponseEntity<String> response = sendEmailForActivation(email, cryptoUserId);

        if (response.getStatusCode() == HttpStatus.OK) {
            return "Вам на пошту (" + email + ") повторно відправлено листа. Перейдіть за посиланням для підтвердження.";
        } else {
            log.error("Failed to resend activation email to {} for user {}. Mail service response: {}", email, appUser.getTelegramUserId(), response.getBody());
            // Важливо не змінювати стан користувача, він залишається WAIT_FOR_EMAIL_STATE
            return "На жаль, не вдалося повторно відправити лист активації на адресу " + email + ". Спробуйте пізніше або зверніться до підтримки.";
        }
    }
}