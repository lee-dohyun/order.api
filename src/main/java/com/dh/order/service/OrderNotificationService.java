package com.dh.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import com.dh.order.domain.Order;

@Service
public class OrderNotificationService {

    private static final Logger log = LoggerFactory.getLogger(OrderNotificationService.class);

    private final JavaMailSender mailSender;
    private final String mailFrom;

    public OrderNotificationService(
            JavaMailSender mailSender,
            @Value("${app.mail-from}") String mailFrom) {
        this.mailSender = mailSender;
        this.mailFrom = mailFrom;
    }

    // 게스트 주문(customerEmail 없음)은 스킵. 메일 발송 실패가 결제 자체를 실패시키면 안 되므로 예외를 여기서 흡수.
    public void notifyPaid(Order order) {
        if (order.getCustomerEmail() == null || order.getCustomerEmail().isBlank()) {
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(order.getCustomerEmail());
            message.setSubject("[주문 결제 완료] 주문 #" + order.getId());
            message.setText(
                    order.getOrdererName() + "님, 주문이 결제 완료되었습니다.\n\n"
                            + "주문번호: " + order.getId() + "\n"
                            + "결제금액: " + order.getTotalPrice() + "원\n"
                            + "배송지: " + order.getShippingAddress() + "\n");
            mailSender.send(message);
        } catch (MailException e) {
            log.warn("주문 결제 알림 메일 발송 실패 (orderId={})", order.getId(), e);
        }
    }
}
