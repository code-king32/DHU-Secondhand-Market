package com.dhu.secondhand.identity.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class MailService {

    private final JavaMailSender mailSender;
    private final String from;

    public MailService(
            JavaMailSender mailSender,
            @Value("${spring.mail.username}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    public void sendVerificationCode(String email, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("校园二手平台邮箱验证码");
        message.setText("您本次的邮箱验证码是：" + code
                + "。请勿将验证码透露给他人。");

        mailSender.send(message);
    }
}