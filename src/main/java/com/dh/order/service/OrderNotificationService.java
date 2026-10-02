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

import com.dh.order.config.AuthApiClient;
import com.dh.order.config.AuthApiClient.MemberNotification;
import com.dh.order.config.Messages;
import com.dh.order.config.MoneyFormatter;
import com.dh.order.domain.Order;

@Service
public class OrderNotificationService {

    private static final Logger log = LoggerFactory.getLogger(OrderNotificationService.class);

    /** 알림을 눌렀을 때 가는 곳. 주문 내역은 마이페이지에 있다(공통 헤더의 "주문조회"와 같은 주소). */
    static final String ORDER_LINK = "https://customer.posselect.com/mypage";

    private final JavaMailSender mailSender;
    private final AuthApiClient authApiClient;
    private final Messages messages;
    private final String mailFrom;
    private final Currency baseCurrency;

    public OrderNotificationService(
            JavaMailSender mailSender,
            AuthApiClient authApiClient,
            Messages messages,
            @Value("${app.mail-from}") String mailFrom,
            @Value("${app.base-currency}") String baseCurrency) {
        this.mailSender = mailSender;
        this.authApiClient = authApiClient;
        this.messages = messages;
        this.mailFrom = mailFrom;
        this.baseCurrency = Currency.getInstance(baseCurrency);
    }

    /**
     * 결제 완료 통지 — 회원 알림함(헤더 알림)과 메일. 둘은 서로 독립이다: 한쪽이 실패하거나 대상이 없어도
     * 다른 쪽은 보낸다. 어느 쪽 실패도 결제 자체를 실패시키면 안 되므로 예외는 각자 흡수한다.
     */
    public void notifyPaid(Order order) {
        // 결제 요청을 보낸 고객 본인의 요청 스레드에서 호출되므로 요청 로케일이 곧 고객의 언어다.
        // 나중에 배치/관리자 트리거로 통지를 보내게 되면 회원의 선호 언어를 저장해서 써야 한다.
        String amount = MoneyFormatter.format(order.getTotalPrice(), baseCurrency, LocaleContextHolder.getLocale());
        notifyInbox(order, amount);
        notifyByMail(order, amount);
    }

    // 게스트 주문(customerId 없음)은 알림함이 없다. dedupKey 는 주문당 하나 — 결제 확정이 재시도돼도 한 건만 남는다.
    private void notifyInbox(Order order, String amount) {
        if (order.getCustomerId() == null || order.getCustomerId().isBlank()) {
            return;
        }
        try {
            authApiClient.sendNotification(new MemberNotification(
                    order.getCustomerId(),
                    "ORDER_PAID",
                    messages.get("notification.orderPaid.title"),
                    // 주문번호는 문자열로 넘긴다 — 숫자로 넘기면 MessageFormat 이 자릿수 구분자를 붙인다.
                    messages.get("notification.orderPaid.body", String.valueOf(order.getId()), amount),
                    ORDER_LINK,
                    "order-paid:" + order.getId()));
        } catch (RuntimeException e) {
            // 서킷브레이커 폴백이 이미 흡수하지만, 프록시를 안 거치는 호출(테스트 등)에서도 결제를 깨지 않게 한 번 더 막는다.
            log.warn("주문 결제 알림 등록 실패 (orderId={})", order.getId(), e);
        }
    }

    // 게스트 주문(customerEmail 없음)은 스킵. 메일 발송 실패가 결제 자체를 실패시키면 안 되므로 예외를 여기서 흡수.
    private void notifyByMail(Order order, String amount) {
        if (order.getCustomerEmail() == null || order.getCustomerEmail().isBlank()) {
            return;
        }
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
