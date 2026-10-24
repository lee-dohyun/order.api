package com.dh.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.dh.order.config.AuthApiClient;
import com.dh.order.config.AuthApiClient.MemberNotification;
import com.dh.order.config.Messages;
import com.dh.order.domain.NotificationFailure;
import com.dh.order.domain.Order;
import com.dh.order.domain.OrderItem;
import com.dh.order.repository.NotificationFailureRepository;
import com.dh.order.repository.OrderRepository;

/** 결제 완료 통지 — 알림함 등록과 메일이 서로 독립이고, 어느 쪽 실패도 밖으로 새지 않는지(gateway#180). */
class OrderNotificationServiceTest {

    private JavaMailSender mailSender;
    private AuthApiClient authApiClient;
    private OrderRepository orderRepository;
    private NotificationFailureRepository failureRepository;
    private OrderNotificationService service;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        authApiClient = mock(AuthApiClient.class);
        orderRepository = mock(OrderRepository.class);
        failureRepository = mock(NotificationFailureRepository.class);
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames("messages", "mail_messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        service = new OrderNotificationService(mailSender, authApiClient, orderRepository, failureRepository,
                new Messages(source), "no-reply@test", "KRW");
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

    @Test
    @DisplayName("비회원 주문은 주문할 때 받은 이메일로 보내고, 본문에 조회 링크(주문번호·게스트 토큰)를 넣는다 (gateway#323)")
    void 비회원_메일과_조회_링크() {
        Order guest = order(77L, null, null);
        when(guest.getGuestEmail()).thenReturn("guest@test");
        when(guest.getGuestToken()).thenReturn("tok-123");

        service.notifyPaid(guest);

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        assertThat(mail.getValue().getTo()).containsExactly("guest@test");
        assertThat(mail.getValue().getText()).contains("https://product.posselect.com/guest-order?id=77&token=tok-123");
        verify(authApiClient, never()).sendNotification(any());
    }

    @Test
    @DisplayName("회원 메일에는 비회원 조회 링크를 넣지 않는다")
    void 회원_메일에는_조회_링크_없음() {
        service.notifyPaid(order(5L, "sub-1", "a@test"));

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        assertThat(mail.getValue().getText()).doesNotContain("guest-order");
    }

    private Order orderWithItems(Long id, String email) {
        Order order = order(id, "sub-1", email);
        OrderItem item = mock(OrderItem.class);
        when(item.getProductName()).thenReturn("무선 이어폰");
        when(item.getQuantity()).thenReturn(2);
        when(order.getItems()).thenReturn(List.of(item));
        when(orderRepository.findByIdWithItems(id)).thenReturn(Optional.of(order));
        return order;
    }

    @Test
    @DisplayName("출고 메일에는 택배사·운송장·주문 상품 목록·조회 링크가 들어간다 (order.api#44)")
    void 출고_메일() {
        orderWithItems(9L, "a@test");
        // 관리자 요청 로케일이 영어여도 고객 메일은 한국어로 나간다.
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        service.notifyShipped(9L, "CJ대한통운", "123456789");

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        assertThat(mail.getValue().getTo()).containsExactly("a@test");
        assertThat(mail.getValue().getSubject()).isEqualTo("[상품 출고] 주문 #9");
        assertThat(mail.getValue().getText())
                .contains("CJ대한통운", "123456789", "- 무선 이어폰 × 2", "서울", "wblNo=123456789");
    }

    @Test
    @DisplayName("모르는 택배사는 링크를 만들지 않는다")
    void 모르는_택배사는_링크_없음() {
        orderWithItems(9L, "a@test");

        service.notifyShipped(9L, "동네퀵", "1234");

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        assertThat(mail.getValue().getText()).contains("동네퀵", "1234").doesNotContain("http");
    }

    @Test
    @DisplayName("환불 메일에는 환불 금액·사유·상품 목록이 들어간다")
    void 환불_메일() {
        orderWithItems(9L, "a@test");

        service.notifyRefunded(9L, new BigDecimal("12000"), "단순 변심");

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        assertThat(mail.getValue().getSubject()).isEqualTo("[환불 처리] 주문 #9");
        assertThat(mail.getValue().getText()).contains("12,000", "단순 변심", "- 무선 이어폰 × 2");
    }

    @Test
    @DisplayName("발송이 실패하면 예외를 밖으로 내지 않고 미발송 기록을 남긴다")
    void 발송_실패는_기록() {
        orderWithItems(9L, "a@test");
        org.mockito.Mockito.doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatCode(() -> service.notifyShipped(9L, "CJ대한통운", "1")).doesNotThrowAnyException();

        ArgumentCaptor<NotificationFailure> saved = ArgumentCaptor.forClass(NotificationFailure.class);
        verify(failureRepository).save(saved.capture());
        assertThat(saved.getValue().getOrderId()).isEqualTo(9L);
        assertThat(saved.getValue().getKind()).isEqualTo("SHIPPED");
        assertThat(saved.getValue().getLastError()).contains("smtp down");
    }

    @Test
    @DisplayName("실패 기록 저장까지 실패해도 밖으로 내지 않는다")
    void 기록_실패도_흡수() {
        orderWithItems(9L, "a@test");
        org.mockito.Mockito.doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));
        when(failureRepository.save(any())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> service.notifyRefunded(9L, BigDecimal.ONE, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("받을 이메일이 없는 주문은 보내지도 실패로 기록하지도 않는다")
    void 수신자_없으면_조용히_스킵() {
        orderWithItems(9L, null);

        service.notifyShipped(9L, "CJ대한통운", "1");

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        verify(failureRepository, never()).save(any());
    }
}
