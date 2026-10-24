package com.dh.order.service;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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
import com.dh.order.domain.NotificationFailure;
import com.dh.order.domain.Order;
import com.dh.order.repository.NotificationFailureRepository;
import com.dh.order.repository.OrderRepository;

@Service
public class OrderNotificationService {

    private static final Logger log = LoggerFactory.getLogger(OrderNotificationService.class);

    /** 알림을 눌렀을 때 가는 곳. 주문 내역은 마이페이지에 있다(공통 헤더의 "주문조회"와 같은 주소). */
    static final String ORDER_LINK = "https://customer.posselect.com/mypage";

    /** 비회원 주문 조회 화면(product.front, 로그인 없이 열린다 — gateway#323). 게스트 토큰이 곧 열쇠다. */
    static final String GUEST_ORDER_LINK = "https://product.posselect.com/guest-order?id=%d&token=%s";

    /**
     * 택배사 이름(관리자가 자유 입력)에 이 단어가 들어 있으면 조회 링크를 붙인다. 모르는 택배사는 링크 없이
     * 택배사와 운송장번호만 안내한다 — 추측한 주소를 고객에게 보내지 않는다. 조회 주소는 택배사 공개 페이지 기준이라 바뀔 수 있다.
     */
    static final Map<String, String> TRACKING_URLS = Map.of(
            "대한통운", "https://trace.cjlogistics.com/next/tracking.html?wblNo=%s",
            "CJ", "https://trace.cjlogistics.com/next/tracking.html?wblNo=%s",
            "한진", "https://www.hanjin.com/kor/CMS/DeliveryMgr/WaybillResult.do?mCode=MN038&schLang=KR&wblnumText2=%s",
            "롯데", "https://www.lotteglogis.com/home/reservation/tracking/linkView?InvNo=%s",
            "우체국", "https://service.epost.go.kr/trace.RetrieveDomRigiTraceList.comm?sid1=%s");

    // 관리자가 트리거하는 메일이라 요청 로케일은 관리자의 것이다. 회원 언어를 저장하기 전까지는 한국어로 고정한다.
    private static final Locale ADMIN_TRIGGERED_LOCALE = Locale.KOREAN;

    private final JavaMailSender mailSender;
    private final AuthApiClient authApiClient;
    private final OrderRepository orderRepository;
    private final NotificationFailureRepository failureRepository;
    private final Messages messages;
    private final String mailFrom;
    private final Currency baseCurrency;

    public OrderNotificationService(
            JavaMailSender mailSender,
            AuthApiClient authApiClient,
            OrderRepository orderRepository,
            NotificationFailureRepository failureRepository,
            Messages messages,
            @Value("${app.mail-from}") String mailFrom,
            @Value("${app.base-currency}") String baseCurrency) {
        this.mailSender = mailSender;
        this.authApiClient = authApiClient;
        this.orderRepository = orderRepository;
        this.failureRepository = failureRepository;
        this.messages = messages;
        this.mailFrom = mailFrom;
        this.baseCurrency = Currency.getInstance(baseCurrency);
    }

    /**
     * 출고(운송장 등록) 안내 메일. 운송장 등록이 커밋된 <b>뒤에</b> 컨트롤러가 부른다 — 트랜잭션 안에서 SMTP 를
     * 기다리지 않고, 메일이 실패해도 출고는 되돌리지 않는다. 실패는 {@code notification_failures} 에 남는다.
     */
    public void notifyShipped(Long orderId, String carrier, String trackingNumber) {
        sendOrderMail(orderId, "SHIPPED", order -> {
            String trackLink = trackingUrl(carrier, trackingNumber);
            String body = messages.get(ADMIN_TRIGGERED_LOCALE, "email.orderShipped.body",
                    order.getOrdererName(), String.valueOf(order.getId()), carrier, trackingNumber,
                    itemLines(order), order.getShippingAddress());
            if (trackLink != null) {
                body += messages.get(ADMIN_TRIGGERED_LOCALE, "email.orderShipped.trackLink", trackLink);
            }
            return new String[] {
                    messages.get(ADMIN_TRIGGERED_LOCALE, "email.orderShipped.subject", String.valueOf(order.getId())),
                    body };
        });
    }

    /** 환불 안내 메일. 환불이 커밋된 뒤에 부른다 — 이유는 {@link #notifyShipped} 와 같다. */
    public void notifyRefunded(Long orderId, BigDecimal amount, String reason) {
        sendOrderMail(orderId, "REFUNDED", order -> {
            String formatted = MoneyFormatter.format(amount, baseCurrency, ADMIN_TRIGGERED_LOCALE);
            return new String[] {
                    messages.get(ADMIN_TRIGGERED_LOCALE, "email.orderRefunded.subject", String.valueOf(order.getId())),
                    messages.get(ADMIN_TRIGGERED_LOCALE, "email.orderRefunded.body",
                            order.getOrdererName(), String.valueOf(order.getId()), formatted,
                            reason == null || reason.isBlank() ? "-" : reason, itemLines(order)) };
        });
    }

    // 제목·본문을 만드는 일까지 try 안에 둔다 — 주문 조회나 문구 조립이 던져도 이미 끝난 출고·환불을 깨면 안 된다.
    private void sendOrderMail(Long orderId, String kind, Function<Order, String[]> subjectAndBody) {
        try {
            Order order = orderRepository.findByIdWithItems(orderId).orElse(null);
            if (order == null) {
                return;
            }
            String to = recipient(order);
            if (to == null) {
                return;
            }
            String[] mail = subjectAndBody.apply(order);
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(mailFrom);
                message.setTo(to);
                message.setSubject(mail[0]);
                message.setText(mail[1]);
                mailSender.send(message);
            } catch (MailException e) {
                log.warn("주문 {} 메일 발송 실패 (orderId={})", kind, orderId, e);
                recordFailure(orderId, kind, e);
            }
        } catch (RuntimeException e) {
            log.warn("주문 {} 메일 준비 실패 (orderId={})", kind, orderId, e);
            recordFailure(orderId, kind, e);
        }
    }

    private void recordFailure(Long orderId, String kind, RuntimeException cause) {
        try {
            failureRepository.save(new NotificationFailure(orderId, kind, cause.toString()));
        } catch (RuntimeException e) {
            log.warn("메일 실패 기록도 저장하지 못했다 (orderId={}, kind={})", orderId, kind, e);
        }
    }

    // 회원은 계정 이메일, 비회원은 주문할 때 받은 이메일(gateway#323). 둘 다 없으면 null.
    private static String recipient(Order order) {
        boolean guest = order.getCustomerEmail() == null || order.getCustomerEmail().isBlank();
        String to = guest ? order.getGuestEmail() : order.getCustomerEmail();
        return to == null || to.isBlank() ? null : to;
    }

    private String itemLines(Order order) {
        return order.getItems().stream()
                .map(item -> messages.get(ADMIN_TRIGGERED_LOCALE, "email.itemLine",
                        item.getProductName(), String.valueOf(item.getQuantity())))
                .collect(Collectors.joining("\n"));
    }

    static String trackingUrl(String carrier, String trackingNumber) {
        if (carrier == null || trackingNumber == null) {
            return null;
        }
        String normalized = carrier.toUpperCase(Locale.ROOT);
        return TRACKING_URLS.entrySet().stream()
                .filter(e -> normalized.contains(e.getKey().toUpperCase(Locale.ROOT)))
                .map(e -> e.getValue().formatted(URLEncoder.encode(trackingNumber.trim(), StandardCharsets.UTF_8)))
                .findFirst()
                .orElse(null);
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

    // 회원은 계정 이메일, 비회원은 주문할 때 받은 이메일로 보낸다(gateway#323). 둘 다 없으면 스킵.
    // 메일 발송 실패가 결제 자체를 실패시키면 안 되므로 예외를 여기서 흡수.
    private void notifyByMail(Order order, String amount) {
        boolean guest = order.getCustomerEmail() == null || order.getCustomerEmail().isBlank();
        String to = guest ? order.getGuestEmail() : order.getCustomerEmail();
        if (to == null || to.isBlank()) {
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(to);
            // 주문번호는 문자열로 넘긴다 — 숫자로 넘기면 MessageFormat이 자릿수 구분자를 붙여 "1,234"가 된다.
            message.setSubject(messages.get("email.orderPaid.subject", String.valueOf(order.getId())));
            String body = messages.get(
                    "email.orderPaid.body",
                    order.getOrdererName(),
                    String.valueOf(order.getId()),
                    amount,
                    order.getShippingAddress());
            if (guest && order.getGuestToken() != null) {
                // 비회원은 이 링크가 주문을 다시 여는 방법이다 — 창을 닫아도 주문 상태를 볼 수 있다.
                body += messages.get("email.orderPaid.guestLink",
                        GUEST_ORDER_LINK.formatted(order.getId(), order.getGuestToken()));
            }
            message.setText(body);
            mailSender.send(message);
        } catch (MailException e) {
            log.warn("주문 결제 알림 메일 발송 실패 (orderId={})", order.getId(), e);
        }
    }
}
