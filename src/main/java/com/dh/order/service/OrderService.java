package com.dh.order.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dh.order.config.AuthApiClient;
import com.dh.order.config.ProductApiClient;
import com.dh.order.config.ProductApiClient.ResolvedOffer;
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
    private final OrderPaymentFinalizer orderPaymentFinalizer;
    private final InventoryCompensationStore inventoryCompensationStore;
    private final InventoryCompensator inventoryCompensator;
    private final AuthApiClient authApiClient;

    public OrderService(
            OrderRepository orderRepository,
            ShipmentRepository shipmentRepository,
            PaymentRepository paymentRepository,
            RefundRepository refundRepository,
            OrderNotificationService notificationService,
            ProductApiClient productApiClient,
            com.dh.order.repository.ChannelRepository channelRepository,
            OrderPaymentFinalizer orderPaymentFinalizer,
            InventoryCompensationStore inventoryCompensationStore,
            InventoryCompensator inventoryCompensator,
            AuthApiClient authApiClient) {
        this.orderRepository = orderRepository;
        this.shipmentRepository = shipmentRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.notificationService = notificationService;
        this.productApiClient = productApiClient;
        this.channelRepository = channelRepository;
        this.orderPaymentFinalizer = orderPaymentFinalizer;
        this.inventoryCompensationStore = inventoryCompensationStore;
        this.inventoryCompensator = inventoryCompensator;
        this.authApiClient = authApiClient;
    }

    /**
     * 주문을 만든다. 금액은 클라이언트가 보낸 값이 아니라 product.api가 확정해 준 가격으로만
     * 산정한다 — 예전에는 요청 본문의 price를 그대로 믿어서 임의 금액 주문이 가능했다(#232).
     *
     * <p>{@code NOT_SUPPORTED}인 이유: 가격 조회가 원격 HTTP 호출이라 트랜잭션 안에서 하면
     * 커넥션을 잡은 채 네트워크를 기다리게 된다. 저장은 아래 {@code orderRepository.save()}가
     * 자체 트랜잭션으로 처리한다. 이 클래스는 클래스 레벨이 {@code readOnly = true}라서
     * 명시적으로 끊어주지 않으면 읽기 전용 트랜잭션에 합류한다(#211에서 겪은 함정).
     *
     * <p>{@code idempotencyKey}(null 가능)가 있으면 같은 키의 재시도는 새 주문을 만들지 않고 처음 만든
     * 주문을 돌려받는다(gateway#306). 순서는 조회 → 생성 → 유니크 인덱스(V10) 충돌 시 재조회다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OrderResponse createOrder(Long channelId, OrderCreateRequest request, Requester requester,
            String idempotencyKey) {
        // 같은 키의 재시도면 가격 확정(원격 호출)까지 가지 않고 처음 만든 주문을 돌려준다(gateway#306).
        String requestHash = idempotencyKey == null ? null : requestHash(channelId, request);
        if (idempotencyKey != null) {
            Optional<OrderResponse> replayed = replay(idempotencyKey, requestHash, requester);
            if (replayed.isPresent()) {
                return replayed.get();
            }
        }

        // offerId 로 온 품목은 그 오퍼로, variantId 만 온 품목은 product.api 가 고른 대표 오퍼로 확정한다
        // (order.api#14). 두 경로 모두 가격·상품·판매자를 서버가 채운다 — 클라이언트 값은 쓰지 않는다.
        List<Long> offerIds = request.items().stream()
                .map(OrderItemRequest::offerId).filter(Objects::nonNull).distinct().toList();
        List<Long> variantIds = request.items().stream()
                .filter(i -> i.offerId() == null)
                .map(OrderItemRequest::variantId).filter(Objects::nonNull).distinct().toList();
        Map<Long, ResolvedOffer> byOfferId = productApiClient.resolveOffers(offerIds);
        Map<Long, ResolvedOffer> byVariantId = productApiClient.resolveFeaturedOffersByVariant(variantIds);

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

        checkPurchaseLimits(request.items(), byOfferId, byVariantId);
        checkStock(request.items(), byOfferId, byVariantId);

        BigDecimal total = BigDecimal.ZERO;
        for (OrderItemRequest itemRequest : request.items()) {
            // 조회에서 빠졌거나(없는 오퍼, ACTIVE 오퍼가 없는 SKU) 주문 불가인 오퍼는 주문을 만들지
            // 않는다. 가격을 모르는 채로 주문을 생성하면 결제 금액을 확정할 수 없다. active 는 오퍼
            // 상태뿐 아니라 숨김 상품·판매 기간·판매자 정지까지 합친 값이다(product.api#108).
            ResolvedOffer offer = offerFor(itemRequest, byOfferId, byVariantId);
            if (offer == null || !offer.active()) {
                throw new OrderStateException("order.itemUnavailable");
            }
            OrderItem item = new OrderItem();
            // productId/variantId/productName 도 클라이언트 값을 쓰지 않는다 — 어긋난 조합을 보내
            // 다른 상품인 것처럼 기록되게 하는 걸 막는다. variantId 는 재고 차감 기준 키다.
            item.setOfferId(offer.offerId());
            item.setProductId(offer.productId());
            item.setVariantId(offer.variantId());
            item.setProductName(offer.productName());
            item.setPrice(offer.price());
            item.setQuantity(itemRequest.quantity());
            // 판매자는 확정된 오퍼의 판매자를 스냅샷으로 옮긴다(order.api#13). 스냅샷이라 이후
            // 판매자가 상호를 바꾸거나 나가도 이 주문의 기록은 바뀌지 않는다.
            item.setSellerId(offer.sellerId());
            item.setSellerName(offer.sellerName());
            order.addItem(item);
            total = total.add(offer.price().multiply(BigDecimal.valueOf(itemRequest.quantity())));
        }
        applyGradeDiscount(order, total, requester);
        order.setIdempotencyKey(idempotencyKey);
        order.setIdempotencyRequestHash(requestHash);

        Order saved;
        try {
            saved = orderRepository.save(order);
        } catch (DataIntegrityViolationException e) {
            // 같은 키의 요청이 동시에 들어와 둘 다 위의 조회에서 "없음"을 본 경우다. 유니크 인덱스(V10)에
            // 진 쪽이 여기로 오고, 이긴 쪽이 만든 주문을 돌려받는다. 키와 무관한 제약 위반은 그대로 던진다.
            if (idempotencyKey == null) {
                throw e;
            }
            return replay(idempotencyKey, requestHash, requester).orElseThrow(() -> e);
        }
        // 게스트 토큰은 생성 응답에서만 내려준다 - 이후 조회 응답에는 실리지 않는다.
        return toResponse(saved, saved.getGuestToken());
    }

    /**
     * 결제 금액을 "상품 합계 - 회원 등급 할인"으로 확정하고 그 근거를 주문에 스냅샷으로 남긴다(gateway#82).
     *
     * <p>할인율은 요청 본문이 아니라 auth.api 에서만 온다 — 가격을 product.api 에서 확정받는 것과 같은
     * 이유다. 로그인하지 않은 게스트는 등급이 없으므로 조회하지 않는다. 레거시 이메일 폴백도 쓰지
     * 않는다: 등급은 금전적 혜택이라 "아마 이 사람일 것"으로 줄 수 없다.
     *
     * <p>할인액은 원 미만을 <b>버린다</b>. 저장 통화가 KRW 단일이라 소수 금액이 없고, 반올림하면
     * 약속한 할인율보다 더 깎는 경우가 생긴다.
     */
    private void applyGradeDiscount(Order order, BigDecimal subtotal, Requester requester) {
        BigDecimal discount = BigDecimal.ZERO;
        if (requester.userId() != null) {
            AuthApiClient.MemberGrade grade = authApiClient.findMemberGrade(requester.userId()).orElse(null);
            if (grade != null && grade.discountRate() != null && grade.discountRate().signum() > 0) {
                discount = subtotal.multiply(grade.discountRate())
                        .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);
                order.setGradeCode(grade.code());
                order.setGradeDiscountRate(grade.discountRate());
            }
        }
        order.setDiscountAmount(discount);
        order.setTotalPrice(subtotal.subtract(discount));
    }

    /**
     * 상품별 1회 최대 구매 수량(product.api#97). 같은 상품의 SKU 를 여러 줄로 나눠도 합계로 본다.
     * product.api 장바구니도 같은 규칙으로 막지만, 주문 API 를 직접 부르면 장바구니를 거치지 않으므로
     * 주문 금액과 마찬가지로 여기서 다시 확인한다. 조회에 없는 variant 는 아래 루프가 itemUnavailable 로 거른다.
     */
    /**
     * 품목이 가리키는 확정 오퍼. {@code offerId} 로 온 품목은 그 오퍼, {@code variantId} 만 온 품목은
     * product.api 가 고른 대표 오퍼다(order.api#14). 수량 제한 검사와 아래 금액 산정이 <b>같은 오퍼</b>를
     * 봐야 하므로 찾는 코드를 한 곳에 둔다.
     */
    private static ResolvedOffer offerFor(OrderItemRequest item, Map<Long, ResolvedOffer> byOfferId,
            Map<Long, ResolvedOffer> byVariantId) {
        return item.offerId() != null ? byOfferId.get(item.offerId()) : byVariantId.get(item.variantId());
    }

    /**
     * 상품별 1회 최대 구매 수량 검사(product.api#97). 한도는 오퍼 응답의 {@code maxPurchaseQuantity} 에서
     * 오고, 같은 상품의 SKU·오퍼를 나눠 담아 한도를 넘기는 것을 막기 위해 productId 로 합산한다.
     */
    private static void checkPurchaseLimits(List<OrderItemRequest> items, Map<Long, ResolvedOffer> byOfferId,
            Map<Long, ResolvedOffer> byVariantId) {
        Map<Long, Integer> quantityByProduct = new HashMap<>();
        Map<Long, Integer> maxByProduct = new HashMap<>();
        for (OrderItemRequest item : items) {
            ResolvedOffer offer = offerFor(item, byOfferId, byVariantId);
            if (offer == null) {
                continue;
            }
            quantityByProduct.merge(offer.productId(), item.quantity(), Integer::sum);
            if (offer.maxPurchaseQuantity() != null) {
                maxByProduct.put(offer.productId(), offer.maxPurchaseQuantity());
            }
        }
        maxByProduct.forEach((productId, max) -> {
            if (quantityByProduct.getOrDefault(productId, 0) > max) {
                throw new OrderStateException("order.purchaseLimitExceeded", max);
            }
        });
    }

    /**
     * 재고보다 많은 수량은 주문을 만들 때 거른다(order.api#47). 전에는 결제할 수 없는 주문이 CREATED 로
     * 만들어지고 결제 단계에서야 막혔다.
     *
     * <p><b>조회일 뿐 예약이 아니다.</b> 여기를 통과한 뒤 결제 전에 다른 주문이 재고를 가져갈 수 있고,
     * 그 경우는 지금처럼 결제 때의 차감이 {@link OutOfStockException} 으로 막는다. 재고를 줄이는 곳은
     * 여전히 결제 한 군데다.
     *
     * <p>재고는 variant 단위라, 같은 SKU 를 여러 줄(오퍼가 달라도)로 나눠 담으면 합계로 본다.
     * 판매하지 않는 오퍼는 건너뛴다 - 그쪽은 뒤의 루프가 {@code order.itemUnavailable} 로 안내한다.
     * 재고를 싣지 않은 응답(null)은 "모름"이므로 막지 않는다.
     */
    private static void checkStock(List<OrderItemRequest> items, Map<Long, ResolvedOffer> byOfferId,
            Map<Long, ResolvedOffer> byVariantId) {
        Map<Long, Integer> quantityByVariant = new LinkedHashMap<>();
        Map<Long, ResolvedOffer> offerByVariant = new HashMap<>();
        for (OrderItemRequest item : items) {
            ResolvedOffer offer = offerFor(item, byOfferId, byVariantId);
            if (offer == null || !offer.active() || offer.stockQuantity() == null) {
                continue;
            }
            quantityByVariant.merge(offer.variantId(), item.quantity(), Integer::sum);
            offerByVariant.put(offer.variantId(), offer);
        }
        quantityByVariant.forEach((variantId, quantity) -> {
            ResolvedOffer offer = offerByVariant.get(variantId);
            int stock = offer.stockQuantity();
            if (stock <= 0) {
                throw new OrderStateException("order.soldOut", offer.productName());
            }
            if (quantity > stock) {
                throw new OrderStateException("order.insufficientStock", offer.productName(), stock);
            }
        });
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
     *
     * <p>차감을 보내기 <b>전에</b> 결제 시도를 {@code inventory_compensations}에 남긴다(#34). 차감과
     * 로컬 커밋 사이에 파드가 죽으면 예외 경로가 돌지 않아 보상할 주체가 없는데, 이 행이 남아 있으면
     * {@link InventoryCompensator#sweep}이 방치된 시도로 보고 재고를 되돌린다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OrderResponse payOrder(Long id, Requester requester) {
        Order order = loadAccessibleWithItems(id, requester);
        if (order.getStatus() != OrderStatus.CREATED) {
            throw new OrderStateException("order.alreadyPaid", String.valueOf(id));
        }

        // 복원이 진행 중인 주문은 받지 않는다 - 지금 차감하면 뒤따라 도착한 복원이 그 차감을 되돌린다.
        if (!inventoryCompensationStore.beginPayment(order.getId())) {
            throw new OrderStateException("order.inventoryUnavailable");
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
            handlePaymentCommitFailure(id, commitFailure);
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
    private void handlePaymentCommitFailure(Long id, RuntimeException commitFailure) {
        // 재확인 조회 자체가 실패할 수 있다(DB 장애처럼 markPaid를 실패시킨 원인과 같은 원인으로).
        // 이 조회가 죽었다고 보상 복원을 건너뛰면 "동시 경합에서 짐"과 "진짜 실패"를 구분할 수
        // 없게 되므로 "진짜 실패"쪽으로 넘긴다. 보상 쪽이 주문 상태를 다시 읽고(InventoryCompensator),
        // 그때도 못 읽으면 미결 행으로 남겨 나중에 판정하므로 승자의 차감을 잘못 되돌리지 않는다.
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
        // 여기서 복원이 실패해도 미결 행이 남아 스케줄러가 성공할 때까지 재시도한다(#34).
        inventoryCompensator.compensatePaymentNow(id);
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
        // 재고 복원 예약을 REFUNDED 전이와 같은 트랜잭션에 넣는다(#34). 복원 호출은 커밋 뒤에 컨트롤러가
        // 하는데, 그 호출이 실패하거나 그 전에 죽어도 이 행이 남아 있어 스케줄러가 이어받는다.
        inventoryCompensationStore.recordRefund(orderId);
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

    private List<OrderItemResponse> toItemResponses(List<OrderItem> items) {
        return items.stream()
                .map(i -> new OrderItemResponse(
                        i.getProductId(), i.getVariantId(), i.getProductName(), i.getPrice(), i.getQuantity(),
                        i.getSellerId(), i.getSellerName(), i.getOfferId()))
                .toList();
    }

    /**
     * 멱등 키로 이미 만들어진 주문이 있으면 처음 응답과 같은 내용을 돌려준다.
     *
     * <p>게스트 토큰도 다시 싣는다 — 첫 응답을 못 받은 게스트(게이트웨이 타임아웃)는 이 토큰 없이는 결제도
     * 조회도 못 한다. 키는 클라이언트가 만든 UUID 라 토큰과 같은 수준의 비밀이고, 아래 두 검사를 통과한
     * 요청에만 내려간다.
     *
     * @throws OrderStateException 같은 키인데 요청자나 주문 내용이 다르면. 예전 주문을 새 주문인 것처럼
     *                             돌려주면 고객이 장바구니와 다른 주문을 결제하게 되고, 요청자가 다른데
     *                             돌려주면 남의 주문이 새어 나간다.
     */
    private Optional<OrderResponse> replay(String idempotencyKey, String requestHash, Requester requester) {
        return orderRepository.findByIdempotencyKeyWithItems(idempotencyKey).map(existing -> {
            boolean sameRequester = Objects.equals(existing.getCustomerId(), requester.userId());
            if (!sameRequester || !Objects.equals(existing.getIdempotencyRequestHash(), requestHash)) {
                throw new OrderStateException("order.idempotencyKeyReused");
            }
            return toResponse(existing, existing.getGuestToken());
        });
    }

    /**
     * 요청 내용의 지문. record 의 toString 은 모든 구성 요소를 선언 순서대로 담으므로 같은 본문이면 같은
     * 문자열이다. 클라이언트가 재시도에서 본문을 바꾸지 않았는지만 보면 되므로 정규화는 하지 않는다.
     */
    private static String requestHash(Long channelId, OrderCreateRequest request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((channelId + "|" + request).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
                order.getTotalPrice().add(order.getDiscountAmount()),
                order.getDiscountAmount(),
                order.getGradeCode(),
                order.getGradeDiscountRate(),
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
