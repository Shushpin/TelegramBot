package lnu.study.service.impl;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lnu.study.dto.MailParams;
import lnu.study.service.MailSenderService;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Log4j2
@Service
public class MailSenderServiceImpl implements MailSenderService {

    private final JavaMailSender javaMailSender;
    @Value("${spring.mail.username}")
    private String emailFrom;
    @Value("${service.activation.uri}")
    private String activationServiceUri;
    @Value("${spring.mail.personal-name}")
    private String personalName;

    public MailSenderServiceImpl(JavaMailSender javaMailSender) {
        this.javaMailSender = javaMailSender;
    }

    @Override
    public void send(MailParams mailParams) {
        try {
            var subject = "Активація облікового запису";
            var messageBody = getActivatonMailBody(mailParams.getId());
            var emailTo = mailParams.getEmailTo();

            MimeMessage mimeMessage = javaMailSender.createMimeMessage();


            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, false, "UTF-8");

            helper.setFrom(emailFrom, personalName);
            helper.setTo(emailTo);
            helper.setSubject(subject);
            helper.setText(messageBody);

            javaMailSender.send(mimeMessage);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            log.error("Помилка при відправці листа активації на {}", mailParams.getEmailTo(), e);
            throw new RuntimeException("Не вдалося надіслати листа", e);
        }
    }


    private String getActivatonMailBody(String id) {
        if (id == null || id.isBlank()) {
            log.error("Отримано порожній ID/хеш для генерації посилання активації.");

            return "Помилка: Не вдалося згенерувати посилання для активації. Будь ласка, спробуйте пізніше або зверніться до підтримки.";
        }

        String activationLink = activationServiceUri.replace("{id}", id);

        String messageBody = String.format(
                "Для завершення реєстрації перейдіть по ссилці активації:\n%s",
                activationLink
        );

        return messageBody;
    }
}
