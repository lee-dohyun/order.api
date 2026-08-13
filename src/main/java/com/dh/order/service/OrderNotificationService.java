package com.dh.order.service;

import java.util.Currency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import com.dh.order.config.Messages;
import com.dh.order.config.MoneyFormatter;
import com.dh.order.domain.Order;

@Service
public class OrderNotificationService {

    private static final Logger log = LoggerFactory.getLogger(OrderNotificationService.class);

    private final JavaMailSender mailSender;
    private final Messages messages;
    private final String mailFrom;
    private final Currency baseCurrency;

    public OrderNotificationService(
            JavaMailSender mailSender,
            Messages messages,
            @Value("${app.mail-from}") String mailFrom,
            @Value("${app.base-currency}") String baseCurrency) {
        this.mailSender = mailSender;
        this.messages = messages;
        this.mailFrom = mailFrom;
        this.baseCurrency = Currency.getInstance(baseCurrency);
    }

    // 게스트 주문(customerEmail 없음)은 스킵. 메일 발송 실패가 결제 자체를 실패시키면 안 되므로 예외를 여기서 흡수.
    public void notifyPaid(Order order) {
        if (order.getCustomerEmail() == null || order.getCustomerEmail().isBlank()) {
            return;
        }
        // 결제 요청을 보낸 고객 본인의 요청 스레드에서 호출되므로 요청 로케일이 곧 고객의 언어다.
        // 나중에 배치/관리자 트리거로 메일을 보내게 되면 회원의 선호 언어를 저장해서 써야 한다.
        String amount = MoneyFormatter.format(order.getTotalPrice(), baseCurrency, LocaleContextHolder.getLocale());
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(order.getCustomerEmail());
            // 주문번호는 문자열로 넘긴다 — 숫자로 넘기면 MessageFormat이 자릿수 구분자를 붙여 "1,234"가 된다.
            message.setSubject(messages.get("email.orderPaid.subject", String.valueOf(order.getId())));
            message.setText(messages.get(
                    "email.orderPaid.body",
                    order.getOrdererName(),
                    String.valueOf(order.getId()),
                    amount,
                    order.getShippingAddress()));
            mailSender.send(message);
        } catch (MailException e) {
            log.warn("주문 결제 알림 메일 발송 실패 (orderId={})", order.getId(), e);
        }
    }
}
