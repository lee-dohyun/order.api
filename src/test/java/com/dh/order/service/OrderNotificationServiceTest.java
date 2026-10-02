package com.dh.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.dh.order.config.AuthApiClient;
import com.dh.order.config.AuthApiClient.MemberNotification;
import com.dh.order.config.Messages;
import com.dh.order.domain.Order;

/** 결제 완료 통지 — 알림함 등록과 메일이 서로 독립이고, 어느 쪽 실패도 밖으로 새지 않는지(gateway#180). */
class OrderNotificationServiceTest {

    private JavaMailSender mailSender;
    private AuthApiClient authApiClient;
    private OrderNotificationService service;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        authApiClient = mock(AuthApiClient.class);
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        service = new OrderNotificationService(mailSender, authApiClient, new Messages(source), "no-reply@test", "KRW");
        LocaleContextHolder.setLocale(Locale.KOREAN);
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    private Order order(Long id, String customerId, String email) {
        Order order = mock(Order.class);
        when(order.getId()).thenReturn(id);
        when(order.getCustomerId()).thenReturn(customerId);
        when(order.getCustomerEmail()).thenReturn(email);
        when(order.getTotalPrice()).thenReturn(new BigDecimal("12000"));
        when(order.getOrdererName()).thenReturn("홍길동");
        when(order.getShippingAddress()).thenReturn("서울");
        return order;
    }

    @Test
    @DisplayName("회원 주문은 주문당 하나의 dedupKey 로 알림함에 등록된다")
    void 회원_주문은_알림함에_등록() {
        service.notifyPaid(order(1234L, "sub-1", "a@test"));

        ArgumentCaptor<MemberNotification> captor = ArgumentCaptor.forClass(MemberNotification.class);
        verify(authApiClient).sendNotification(captor.capture());
        MemberNotification sent = captor.getValue();
        assertThat(sent.userId()).isEqualTo("sub-1");
        assertThat(sent.type()).isEqualTo("ORDER_PAID");
        assertThat(sent.dedupKey()).isEqualTo("order-paid:1234");
        assertThat(sent.title()).isEqualTo("결제가 완료되었습니다");
        assertThat(sent.body()).as("주문번호에 자릿수 구분자가 붙지 않는다").startsWith("주문 #1234 · ");
        assertThat(sent.linkUrl()).startsWith("https://");
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("게스트 주문(customerId 없음)은 알림함에 넣지 않는다")
    void 게스트_주문은_알림_없음() {
        service.notifyPaid(order(1L, null, null));

        verify(authApiClient, never()).sendNotification(any());
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("알림 등록이 예외를 던져도 결제 흐름을 깨지 않고 메일은 그대로 보낸다")
    void 알림_실패는_흡수() {
        when(authApiClient.sendNotification(any())).thenThrow(new IllegalStateException("auth.api down"));

        assertThatCode(() -> service.notifyPaid(order(1L, "sub-1", "a@test"))).doesNotThrowAnyException();
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("이메일이 없는 회원 주문도 알림함에는 등록된다")
    void 이메일_없어도_알림은_간다() {
        service.notifyPaid(order(1L, "sub-1", null));

        verify(authApiClient).sendNotification(any());
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }
}
