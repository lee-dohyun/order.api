package com.dh.order.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dh.order.config.ProductApiClient;
import com.dh.order.config.ProductApiClient.ResolvedVariant;
import com.dh.order.domain.Order;
import com.dh.order.domain.OrderItem;
import com.dh.order.domain.OrderStatus;
import com.dh.order.domain.Shipment;
import com.dh.order.domain.ShipmentStatus;
import com.dh.order.dto.OrderDtos.CreateShipmentRequest;
import com.dh.order.dto.OrderDtos.OrderCreateRequest;
import com.dh.order.dto.OrderDtos.OrderItemRequest;
import com.dh.order.dto.OrderDtos.OrderItemResponse;
import com.dh.order.dto.OrderDtos.OrderResponse;
import com.dh.order.dto.OrderDtos.OrderAdminSummaryResponse;
import com.dh.order.dto.OrderDtos.OrderSummaryResponse;
import com.dh.order.dto.OrderDtos.RefundRequest;
import com.dh.order.dto.OrderDtos.RefundResponse;
import com.dh.order.dto.OrderDtos.Requester;
import com.dh.order.dto.OrderDtos.ShipmentResponse;
import com.dh.order.payment.Payment;
import com.dh.order.payment.PaymentRepository;
import com.dh.order.payment.Refund;
import com.dh.order.payment.RefundRepository;
import com.dh.order.repository.OrderRepository;
import com.dh.order.repository.ShipmentRepository;

@Service
@Transactional(readOnly = true)
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final ShipmentRepository shipmentRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final OrderNotificationService notificationService;
    private final ProductApiClient productApiClient;
    private final com.dh.order.repository.ChannelRepository channelRepository;

    public OrderService(
            OrderRepository orderRepository,
            ShipmentRepository shipmentRepository,
            PaymentRepository paymentRepository,
            RefundRepository refundRepository,
            OrderNotificationService notificationService,
            ProductApiClient productApiClient,
            com.dh.order.repository.ChannelRepository channelRepository) {
        this.orderRepository = orderRepository;
        this.shipmentRepository = shipmentRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.notificationService = notificationService;
        this.productApiClient = productApiClient;
        this.channelRepository = channelRepository;
    }

    /**
     * 주문을 만든다. 금액은 클라이언트가 보낸 값이 아니라 product.api가 확정해 준 가격으로만
     * 산정한다 — 예전에는 요청 본문의 price를 그대로 믿어서 임의 금액 주문이 가능했다(#232).
     *
     * <p>{@code NOT_SUPPORTED}인 이유: 가격 조회가 원격 HTTP 호출이라 트랜잭션 안에서 하면
     * 커넥션을 잡은 채 네트워크를 기다리게 된다. 저장은 아래 {@code orderRepository.save()}가
     * 자체 트랜잭션으로 처리한다. 이 클래스는 클래스 레벨이 {@code readOnly = true}라서
     * 명시적으로 끊어주지 않으면 읽기 전용 트랜잭션에 합류한다(#211에서 겪은 함정).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OrderResponse createOrder(Long channelId, OrderCreateRequest request, Requester requester) {
        List<Long> variantIds = request.items().stream().map(OrderItemRequest::variantId).distinct().toList();
        Map<Long, ResolvedVariant> catalog = productApiClient.resolveVariants(variantIds);

        com.dh.order.domain.Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new NoSuchElementException("channel not found: " + channelId));

        Order order = new Order();
        order.setChannel(channel);
        order.setCustomerId(requester.userId());
        order.setCustomerEmail(requester.userEmail());
        // 소유자 계정이 없는 게스트 주문은 이 토큰이 유일한 접근 수단이다.
        if (requester.userId() == null && requester.userEmail() == null) {
            order.setGuestToken(UUID.randomUUID().toString());
        }
        order.setOrdererName(request.ordererName());
        order.setOrdererPhone(request.ordererPhone());
        order.setShippingAddress(request.shippingAddress());
        order.setRecipientName(request.recipientName());
        order.setRecipientPhone(request.recipientPhone());
        order.setZipCode(request.zipCode());
        order.setAddress1(request.address1());
        order.setAddress2(request.address2());

        BigDecimal total = BigDecimal.ZERO;
        for (OrderItemRequest itemRequest : request.items()) {
            // 조회에서 빠졌거나 판매 중지된 variant는 주문을 만들지 않는다. 가격을 모르는 채로
            // 주문을 생성하면 결제 금액을 확정할 수 없다.
            ResolvedVariant variant = catalog.get(itemRequest.variantId());
            if (variant == null || !variant.active()) {
                throw new OrderStateException("order.itemUnavailable");
            }
            OrderItem item = new OrderItem();
            // productId/productName도 클라이언트 값을 쓰지 않는다 — variantId와 어긋난 조합을
            // 보내 다른 상품인 것처럼 기록되게 하는 걸 막는다.
            item.setProductId(variant.productId());
            item.setVariantId(variant.variantId());
            item.setProductName(variant.productName());
            item.setPrice(variant.price());
            item.setQuantity(itemRequest.quantity());
            order.addItem(item);
            total = total.add(variant.price().multiply(BigDecimal.valueOf(itemRequest.quantity())));
        }
        order.setTotalPrice(total);

        Order saved = orderRepository.save(order);
        // 게스트 토큰은 생성 응답에서만 내려준다 - 이후 조회 응답에는 실리지 않는다.
        return toResponse(saved, saved.getGuestToken());
    }

    public OrderResponse getOrder(Long id, Requester requester) {
        return toResponse(loadAccessible(id, requester), null);
    }

    public List<OrderSummaryResponse> getMyOrders(String customerId, String customerEmail) {
        return orderRepository.findMine(customerId, customerEmail).stream()
                .map(o -> new OrderSummaryResponse(o.getId(), o.getStatus().name(), o.getTotalPrice(),
                        o.getItems().size(), o.getCreatedAt()))
                .toList();
    }

    public List<OrderAdminSummaryResponse> getAllOrders() {
        return orderRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(o -> new OrderAdminSummaryResponse(o.getId(), o.getCustomerEmail(), o.getOrdererName(),
                        o.getOrdererPhone(), o.getStatus().name(), o.getTotalPrice(), o.getItems().size(),
                        o.getCreatedAt()))
                .toList();
    }

    // 실제 PG 연동 전까지의 mock 결제 - 항상 성공 처리. 나중에 여기만 실제 PG 클라이언트 호출로 교체하면 됨.
    @Transactional
    public OrderResponse payOrder(Long id, Requester requester) {
        Order order = loadAccessible(id, requester);
        if (order.getStatus() != OrderStatus.CREATED) {
            throw new OrderStateException("order.alreadyPaid", String.valueOf(id));
        }
        // 재고 차감 실패(부족/product.api 오류) 시 예외가 트랜잭션을 롤백시켜 결제 확정 전으로 되돌린다.
        productApiClient.deductInventory(order.getId(), order.getItems());
        order.setStatus(OrderStatus.PAID);
        order.setPaidAt(LocalDateTime.now());
        paymentRepository.save(new Payment(order, order.getTotalPrice(), "MOCK"));
        notificationService.notifyPaid(order);
        return toResponse(order, null);
    }

    /**
     * 주문을 불러오되 요청자가 접근할 수 없으면 존재하지 않는 것처럼 취급한다.
     * 403이 아니라 404인 이유는 주문 ID가 순번이라 "존재하지만 권한 없음"을 구분해 주면
     * 그 자체로 주문 건수/유효 ID 범위가 노출되기 때문이다.
     */
    private Order loadAccessible(Long id, Requester requester) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("order not found: " + id));
        if (requester.admin() || order.isAccessibleBy(
                requester.userId(), requester.userEmail(), requester.guestToken())) {
            return order;
        }
        log.warn("주문 접근 거부 (orderId={}, userId={}, guestToken={})",
                id, requester.userId(), requester.guestToken() != null ? "제시됨" : "없음");
        throw new NoSuchElementException("order not found: " + id);
    }

    /** admin이 환불 처리 - 결제가 존재하는 주문(PAID 이후)만 가능, 전액 환불만 지원한다. */
    @Transactional
    public RefundResponse refundOrder(Long orderId, RefundRequest request) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("order not found: " + orderId));
        if (order.getStatus() == OrderStatus.CREATED || order.getStatus() == OrderStatus.REFUNDED) {
            throw new IllegalStateException("환불할 수 없는 주문 상태입니다: " + orderId);
        }
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new NoSuchElementException("payment not found for order: " + orderId));

        Refund refund = new Refund(payment, payment.getAmount(), request.reason());
        refundRepository.save(refund);
        order.setStatus(OrderStatus.REFUNDED);
        return toRefundResponse(order, refund);
    }

    /** admin이 운송장을 등록 - PAID 주문만 가능, 등록 즉시 SHIPPED로 전이한다. */
    @Transactional
    public ShipmentResponse createShipment(Long orderId, CreateShipmentRequest request) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("order not found: " + orderId));
        if (order.getStatus() != OrderStatus.PAID) {
            throw new IllegalStateException("결제 완료 상태의 주문만 배송을 등록할 수 있습니다: " + orderId);
        }
        if (shipmentRepository.findByOrderId(orderId).isPresent()) {
            throw new IllegalStateException("이미 배송이 등록된 주문입니다: " + orderId);
        }

        Shipment shipment = new Shipment(order, request.carrier(), request.trackingNumber());
        shipmentRepository.save(shipment);
        order.setStatus(OrderStatus.SHIPPED);
        return toShipmentResponse(shipment);
    }

    /** admin이 배송 완료 처리 - SHIPPED 상태만 가능. */
    @Transactional
    public ShipmentResponse markDelivered(Long orderId) {
        Shipment shipment = shipmentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new NoSuchElementException("shipment not found for order: " + orderId));
        if (shipment.getStatus() != ShipmentStatus.SHIPPED) {
            throw new IllegalStateException("배송중 상태의 주문만 배송완료로 처리할 수 있습니다: " + orderId);
        }
        shipment.markDelivered();
        shipment.getOrder().setStatus(OrderStatus.DELIVERED);
        return toShipmentResponse(shipment);
    }

    public ShipmentResponse getShipment(Long orderId) {
        Shipment shipment = shipmentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new NoSuchElementException("shipment not found for order: " + orderId));
        return toShipmentResponse(shipment);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private OrderResponse toResponse(Order order, String guestToken) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(i -> new OrderItemResponse(
                        i.getProductId(), i.getVariantId(), i.getProductName(), i.getPrice(), i.getQuantity()))
                .toList();

        return new OrderResponse(
                order.getId(),
                order.getOrdererName(),
                order.getOrdererPhone(),
                order.getShippingAddress(),
                order.getRecipientName(),
                order.getRecipientPhone(),
                order.getZipCode(),
                order.getAddress1(),
                order.getAddress2(),
                order.getStatus().name(),
                order.getTotalPrice(),
                items,
                order.getCreatedAt(),
                order.getPaidAt(),
                guestToken);
    }

    private RefundResponse toRefundResponse(Order order, Refund refund) {
        return new RefundResponse(
                order.getId(), refund.getAmount(), refund.getReason(), refund.getStatus().name(),
                refund.getRefundedAt());
    }

    private ShipmentResponse toShipmentResponse(Shipment shipment) {
        return new ShipmentResponse(
                shipment.getOrder().getId(),
                shipment.getCarrier(),
                shipment.getTrackingNumber(),
                shipment.getStatus().name(),
                shipment.getShippedAt(),
                shipment.getDeliveredAt());
    }
}
