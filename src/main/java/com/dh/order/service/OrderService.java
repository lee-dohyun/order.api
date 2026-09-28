package com.dh.order.service;

import java.math.BigDecimal;
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

    /**
     * 자사 판매자 식별자. 1P 로 시작하기로 한 결정(gateway#212 결정 0)에 따라 지금은 이 값 하나뿐이다.
     * 3P 로 넘어가면 오퍼가 판매자를 결정하므로 이 상수는 사라진다.
     */
    private static final Long FIRST_PARTY_SELLER_ID = 1L;
    private static final String FIRST_PARTY_SELLER_NAME = "포스셀렉트";

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final ShipmentRepository shipmentRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final OrderNotificationService notificationService;
    private final ProductApiClient productApiClient;
    private final com.dh.order.repository.ChannelRepository channelRepository;
    private final OrderPaymentFinalizer orderPaymentFinalizer;

    public OrderService(
            OrderRepository orderRepository,
            ShipmentRepository shipmentRepository,
            PaymentRepository paymentRepository,
            RefundRepository refundRepository,
            OrderNotificationService notificationService,
            ProductApiClient productApiClient,
            com.dh.order.repository.ChannelRepository channelRepository,
            OrderPaymentFinalizer orderPaymentFinalizer) {
        this.orderRepository = orderRepository;
        this.shipmentRepository = shipmentRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.notificationService = notificationService;
        this.productApiClient = productApiClient;
        this.channelRepository = channelRepository;
        this.orderPaymentFinalizer = orderPaymentFinalizer;
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
            // 지금은 판매자가 자사 한 곳뿐이라 상수다. 3단계(order.api#14)에서 오퍼 참조로 바뀌면
            // offers/resolve 응답의 판매자를 그대로 옮겨 담는 자리가 된다. 상수인 동안에도
            // 값을 남겨 두는 이유는 소급이 불가능하기 때문이다(order.api#13).
            item.setSellerId(FIRST_PARTY_SELLER_ID);
            item.setSellerName(FIRST_PARTY_SELLER_NAME);
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

    /**
     * 실제 PG 연동 전까지의 mock 결제 - 재고 차감이 성공하면 항상 결제 성공 처리한다.
     * 나중에 여기 로컬 커밋 단계({@link OrderPaymentFinalizer})만 실제 PG 클라이언트
     * 호출로 교체하면 됨.
     *
     * <p>{@code createOrder}와 같은 이유로 {@code NOT_SUPPORTED}다 - 재고 차감이 원격
     * HTTP(5초 타임아웃)라 트랜잭션 안에서 기다리면 커넥션을 잡은 채 네트워크를 기다리게
     * 된다. 선착순 이벤트처럼 결제가 몰리는 상황에서 product.api 응답이 늦어지면, 그
     * 트랜잭션이 붙잡은 커넥션들이 order.api 자신의 커넥션 풀을 말려서 이벤트와 무관한
     * 주문까지 실패하게 만든다 - 이게 이 변경의 계기다.
     *
     * <p>로컬 커밋(PAID 전이 + Payment 저장)은 {@link OrderPaymentFinalizer}가 별도
     * 트랜잭션으로 원자적으로 처리한다. 그 커밋이 실패하면(동시 결제 경합에서 졌거나
     * 진짜 오류거나) 이미 나간 재고 차감을 되돌린다 - 이게 이 저장소 AGENTS.md가
     * "payOrder에는 보상 로직이 없다"고 명시했던 결함이다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OrderResponse payOrder(Long id, Requester requester) {
        Order order = loadAccessibleWithItems(id, requester);
        if (order.getStatus() != OrderStatus.CREATED) {
            throw new OrderStateException("order.alreadyPaid", String.valueOf(id));
        }

        // 재고 차감(원격 HTTP) - 트랜잭션 밖에서 호출해 커넥션을 붙잡지 않는다. 멱등성 키는
        // 주문 ID(deductInventory가 {"orderId":..., "items":[...]}를 보낸다) - product.api의
        // 부분 유니크 인덱스(order_id, inventory_id) WHERE type='ORDER_DEDUCT'가 실질적인
        // 중복 차감 방어다. 실패(재고 부족/product.api 오류)하면 예외가 그대로 전파돼
        // 아래 로컬 커밋 자체를 시도하지 않는다 - 아직 아무것도 확정되지 않았으니 보상도 필요 없다.
        productApiClient.deductInventory(order.getId(), order.getItems());

        Order paid;
        try {
            paid = orderPaymentFinalizer.markPaid(order.getId());
        } catch (RuntimeException commitFailure) {
            handlePaymentCommitFailure(id, order, commitFailure);
            throw commitFailure; // handlePaymentCommitFailure는 항상 예외를 던진다 - 컴파일러용.
        }

        // 메일 발송은 롤백 불가능한 부수효과(SMTP)라 로컬 커밋 이후로 뺐다 - 트랜잭션 안에
        // 있으면 응답을 기다리는 동안 DB 커넥션을 붙잡는다.
        notificationService.notifyPaid(paid);
        return toResponse(paid, null);
    }

    /**
     * 재고 차감 이후 로컬 커밋이 실패했을 때의 분기. 두 가지 경우를 구분해야 한다.
     * <ol>
     *   <li><b>동시 결제 경합에서 짐</b>: 다른 요청이 먼저 커밋해 주문이 이미 PAID다.
     *       이 경우 우리가 낸 재고 차감은 애초에 없었던 요청이므로(이 요청은 차감에
     *       성공했지만 결제 확정을 뺏겼다) 복원하면 안 된다 - 복원하면 방금 이긴 요청의
     *       정당한 차감을 우리가 되돌려버리는 사고가 난다. 클라이언트에는 "이미 결제됨"으로
     *       응답한다.</li>
     *   <li><b>진짜 실패</b>(DB 오류 등): 재고는 빠졌는데 주문은 PAID가 아닌 상태로 남을
     *       뻔한 경우다. 방금 나간 차감을 되돌린다(보상 트랜잭션).</li>
     * </ol>
     */
    private void handlePaymentCommitFailure(Long id, Order order, RuntimeException commitFailure) {
        // 재확인 조회 자체가 실패할 수 있다(DB 장애처럼 markPaid를 실패시킨 원인과 같은 원인으로).
        // 이 조회가 죽었다고 보상 복원을 건너뛰면 "동시 경합에서 짐"과 "진짜 실패"를 구분할 수
        // 없게 되는데, 그 경우 안전한 기본값은 "진짜 실패로 보고 보상한다"쪽이다 - 승자의 정당한
        // 차감을 잘못 되돌릴 위험보다 재고가 빠진 채 방치될 위험이 더 크다.
        OrderStatus currentStatus = null;
        try {
            currentStatus = orderRepository.findById(id).map(Order::getStatus).orElse(null);
        } catch (RuntimeException reReadFailure) {
            log.error("결제 확정 실패 후 재확인 조회도 실패 - 보상 복원을 시도한다 (orderId={})", id, reReadFailure);
        }
        if (currentStatus == OrderStatus.PAID) {
            log.info("결제 확정 경합에서 밀렸다 - 다른 요청이 먼저 커밋함 (orderId={})", id);
            throw new OrderStateException("order.alreadyPaid", String.valueOf(id));
        }

        log.error("결제 확정(로컬 커밋) 실패 - 방금 나간 재고 차감을 보상 복원한다 (orderId={})", id, commitFailure);
        try {
            productApiClient.restoreInventory(id, toItemResponses(order.getItems()));
        } catch (Exception restoreFailure) {
            log.error("보상 복원 호출도 실패 - 수동 복원 필요 (orderId={})", id, restoreFailure);
        }
        throw new OrderStateException("order.paymentConfirmationFailed", String.valueOf(id));
    }

    /**
     * 주문을 불러오되 요청자가 접근할 수 없으면 존재하지 않는 것처럼 취급한다.
     * 403이 아니라 404인 이유는 주문 ID가 순번이라 "존재하지만 권한 없음"을 구분해 주면
     * 그 자체로 주문 건수/유효 ID 범위가 노출되기 때문이다.
     */
    private Order loadAccessible(Long id, Requester requester) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("order not found: " + id));
        return checkAccessible(id, order, requester);
    }

    /**
     * payOrder 전용 - items를 JOIN FETCH로 미리 초기화해서 반환한다. payOrder는 재고 차감
     * (원격 호출) 동안 트랜잭션을 들고 있지 않아서({@code NOT_SUPPORTED}), 이 조회의
     * 트랜잭션이 끝나면 엔티티가 곧바로 detach된다 - items가 LAZY인 채로 미초기화 상태면
     * 이후 {@code order.getItems()} 접근이 LazyInitializationException으로 죽는다.
     */
    private Order loadAccessibleWithItems(Long id, Requester requester) {
        Order order = orderRepository.findByIdWithItems(id)
                .orElseThrow(() -> new NoSuchElementException("order not found: " + id));
        return checkAccessible(id, order, requester);
    }

    private Order checkAccessible(Long id, Order order, Requester requester) {
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

    /** payOrder 실패 후 보상 복원 호출과 toResponse가 공유하는 아이템 매핑. */
    private List<OrderItemResponse> toItemResponses(List<OrderItem> items) {
        return items.stream()
                .map(i -> new OrderItemResponse(
                        i.getProductId(), i.getVariantId(), i.getProductName(), i.getPrice(), i.getQuantity(),
                        i.getSellerId(), i.getSellerName()))
                .toList();
    }

    private OrderResponse toResponse(Order order, String guestToken) {
        List<OrderItemResponse> items = toItemResponses(order.getItems());

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
