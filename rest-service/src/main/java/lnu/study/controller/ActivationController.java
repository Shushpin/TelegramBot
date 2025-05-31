package lnu.study.controller;

import lnu.study.service.UserActivationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RequestMapping("/user")
@RestController
public class ActivationController {
    private final UserActivationService userActivationService;

    public ActivationController(UserActivationService userActivationService) {
        this.userActivationService = userActivationService;
    }

    @GetMapping("/activation")
    public ResponseEntity<?> activation(@RequestParam("id") String id) {
        boolean isActivated = userActivationService.activation(id);
        if (isActivated) {
            return ResponseEntity.ok().body("Реєстрація пройшла успішно! " + "Ви можете повернутися в чат до бота для роботи з файлами! ");
        }
        return ResponseEntity.internalServerError().body("Помилка активації. Можливо, посилання недійсне або застаріле.");
    }
}