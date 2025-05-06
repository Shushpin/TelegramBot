package lnu.study.service;

import lnu.study.dto.MailParams;

public interface MailSenderService {
    void send(MailParams mailParams);
}
