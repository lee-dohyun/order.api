package com.dh.order.claim;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.dh.order.claim.ClaimDtos.ClaimCreateRequest;
import com.dh.order.claim.ClaimDtos.ClaimItemRequest;
import com.dh.order.domain.Order;
import com.dh.order.domain.OrderItem;
import com.dh.order.domain.OrderStatus;
import com.dh.order.dto.OrderDtos.Requester;
import com.dh.order.payment.Payment;
import com.dh.order.payment.PaymentRepository;
import com.dh.order.payment.Refund;
import com.dh.order.payment.RefundRepository;
import com.dh.order.repository.OrderRepository;
import com.dh.order.repository.ShipmentRepository;
import com.dh.order.service.InventoryCompensationStore;
import com.dh.order.service.OrderStateException;

/**
 * 클레임의 쓰기 트랜잭션들(gateway#320). 원격 호출(배송비 견적·재고 복원)은 여기서 하지 않는다 — 금액 계산은
 * {@link ClaimService} 가 트랜잭션 밖에서 하고, 여기서는 주문 행을 잠근 채 그 계산의 전제(앞선 완료 클레임 수)가
 * 그대로인지 확인한 뒤 확정한다. 클래스 레벨 readOnly 를 두지 않는다(캐논 §3 전파 함정).
 */
@Component
public class ClaimCommitter {

    /** 반품은 배송 완료 후 이 기간 안에만 받는다. */
    static final int RETURN_WINDOW_DAYS = 7;

    private final OrderRepository orderRepository;
    private final OrderClaimRepository claimRepository;
    private final ShipmentRepository shipmentRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final InventoryCompensationStore compensationStore;
    private final JdbcTemplate jdbc;

    public ClaimCommitter(OrderRepository orderRepository, OrderClaimRepository claimRepository,
            ShipmentRepository shipmentRepository, PaymentRepository paymentRepository,
            RefundRepository refundRepository, InventoryCompensationStore compensationStore, JdbcTemplate jdbc) {
        this.orderRepository = orderRepository;
        this.claimRepository = claimRepository;
        this.shipmentRepository = shipmentRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.compensationStore = compensationStore;
        this.jdbc = jdbc;
    }

    /** 신청 결과. 결제 전 전체 취소처럼 금액 없이 바로 끝난 경우 {@code completed} 가 true 다. */
    public record Created(Long claimId, boolean completed) {
    }

    /**
     * 클레임을 만들고 품목 수량을 잡는다. 소유자가 아니면 404(주문 존재를 드러내지 않는다).
     *
     * <p>결제 전(CREATED) 주문은 차감·결제가 없으므로 부분 취소를 받지 않고, 전체 취소면 그 자리에서 주문을
     * CANCELLED 로 끝낸다. 결제 후 취소는 REQUESTED 로 만들고 호출자가 곧바로 완료시킨다.
     */
    @Transactional
    public Created create(Long orderId, Requester requester, ClaimCreateRequest request, String idempotencyKey) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .filter(o -> requester.admin() || o.isAccessibleBy(requester.userId(), requester.userEmail(), requester.guestToken()))
                .orElseThrow(() -> new NoSuchElementException("order not found: " + orderId));
        Map<Long, OrderItem> itemsById = order.getItems().stream()
                .collect(Collectors.toMap(OrderItem::getId, Function.identity()));
        for (ClaimItemRequest line : request.items()) {
            if (!itemsById.containsKey(line.orderItemId())) {
                throw new OrderStateException("order.claimItemInvalid");
            }
        }

        ClaimReason reason;
        if (request.type() == ClaimType.CANCEL) {
            checkCancellable(order);
            reason = ClaimReason.CHANGE_OF_MIND;
        } else {
            checkReturnable(order);
            if (request.reasonType() == null) {
                throw new OrderStateException("order.claimNotAllowed");
            }
            reason = request.reasonType();
        }

        boolean unpaidWholeCancel = false;
        if (order.getStatus() == OrderStatus.CREATED) {
            // 결제 전에는 차감·결제가 없어 부분 취소를 받으면 결제 금액과 차감 수량을 다시 맞춰야 한다 — 받지 않는다.
            Map<Long, Integer> requested = request.items().stream()
                    .collect(Collectors.toMap(ClaimItemRequest::orderItemId, ClaimItemRequest::quantity, Integer::sum));
            boolean whole = order.getItems().stream()
                    .allMatch(i -> requested.getOrDefault(i.getId(), 0) == i.getQuantity() - i.getClaimedQuantity());
            if (!whole) {
                throw new OrderStateException("order.cancelWholeOrderOnly");
            }
            unpaidWholeCancel = true;
        }

        OrderClaim claim = new OrderClaim();
        claim.setOrder(order);
        claim.setType(request.type());
        claim.setReasonType(reason);
        claim.setReasonDetail(request.reasonDetail());
        claim.setIdempotencyKey(idempotencyKey);
        for (ClaimItemRequest line : request.items()) {
            reserve(orderId, line.orderItemId(), line.quantity());
            claim.addItem(new OrderClaimItem(itemsById.get(line.orderItemId()), line.quantity()));
        }
        if (unpaidWholeCancel) {
            claim.setStatus(ClaimStatus.COMPLETED);
            claim.setDecidedAt(LocalDateTime.now());
            claim.setItemAmount(itemAmount(claim));
            claim.setDiscountShare(BigDecimal.ZERO);
            claim.setShippingRefund(BigDecimal.ZERO);
            claim.setReturnShippingFee(BigDecimal.ZERO);
            claim.setRefundAmount(BigDecimal.ZERO);
            order.setStatus(OrderStatus.CANCELLED);
        }
        claimRepository.save(claim);
        return new Created(claim.getId(), unpaidWholeCancel);
    }

    /**
     * 계산된 금액으로 클레임을 완료한다. 주문 행을 잠근 뒤 앞선 완료 클레임 수가 계산 때와 같은지 본다 — 다르면
     * 다른 클레임이 그 사이에 끝나 할인 안분·배송비 전제가 바뀐 것이라 false 를 돌려주고 호출자가 다시 계산한다.
     */
    @Transactional
    public boolean complete(Long claimId, ClaimReason finalReason, long expectedCompleted,
            ClaimRefundCalculator.Result amounts) {
        OrderClaim claim = claimRepository.findByIdWithItems(claimId)
                .orElseThrow(() -> new NoSuchElementException("claim not found: " + claimId));
        Order order = orderRepository.findByIdForUpdate(claim.getOrder().getId()).orElseThrow();
        if (claim.getStatus() != ClaimStatus.REQUESTED) {
            throw new OrderStateException("order.claimAlreadyDecided");
        }
        if (claimRepository.countCompleted(order.getId()) != expectedCompleted) {
            return false;
        }
        if (claim.getType() == ClaimType.CANCEL) {
            checkCancellable(order);
        }
        claim.setReasonType(finalReason);
        claim.setItemAmount(amounts.itemAmount());
        claim.setDiscountShare(amounts.discountShare());
        claim.setShippingRefund(amounts.shippingRefund());
        claim.setReturnShippingFee(amounts.returnFee());
        claim.setRefundAmount(amounts.refundAmount());
        claim.setStatus(ClaimStatus.COMPLETED);
        claim.setDecidedAt(LocalDateTime.now());

        Payment payment = paymentRepository.findByOrderId(order.getId())
                .orElseThrow(() -> new NoSuchElementException("payment not found for order: " + order.getId()));
        if (amounts.refundAmount().signum() > 0) {
            refundRepository.save(new Refund(payment, amounts.refundAmount(), "claim#" + claim.getId()));
        }
        order.setReturnedItemAmount(order.getReturnedItemAmount()
                .add(amounts.itemAmount().subtract(amounts.discountShare())));

        // 모든 품목이 완료된 클레임으로 돌아갔으면 주문도 끝난다. 반려된 클레임은 수량을 이미 돌려놓았으므로
        // claimed_quantity 가 아니라 완료 클레임 수량으로 센다.
        List<OrderClaim> completed = claimRepository.findByOrderIdWithItems(order.getId()).stream()
                .filter(c -> c.getStatus() == ClaimStatus.COMPLETED).toList();
        int completedQty = completed.stream().flatMap(c -> c.getItems().stream()).mapToInt(OrderClaimItem::getQuantity).sum();
        int totalQty = order.getItems().stream().mapToInt(OrderItem::getQuantity).sum();
        if (completedQty == totalQty) {
            boolean allCancel = completed.stream().allMatch(c -> c.getType() == ClaimType.CANCEL);
            order.setStatus(allCancel ? OrderStatus.CANCELLED : OrderStatus.REFUNDED);
        }
        // 부분 복원 예약을 완료와 같은 트랜잭션에 넣는다 — 복원 호출이 실패해도 행이 남아 스케줄러가 이어받는다.
        compensationStore.recordClaim(order.getId(), claim.getId());
        return true;
    }

    /** 반려 — 잡아 둔 수량을 돌려놓는다. */
    @Transactional
    public void reject(Long claimId, String reason) {
        OrderClaim claim = claimRepository.findByIdWithItems(claimId)
                .orElseThrow(() -> new NoSuchElementException("claim not found: " + claimId));
        orderRepository.findByIdForUpdate(claim.getOrder().getId()).orElseThrow();
        if (claim.getStatus() != ClaimStatus.REQUESTED) {
            throw new OrderStateException("order.claimAlreadyDecided");
        }
        claim.setStatus(ClaimStatus.REJECTED);
        claim.setRejectReason(reason);
        claim.setDecidedAt(LocalDateTime.now());
        for (OrderClaimItem item : claim.getItems()) {
            jdbc.update("UPDATE order_items SET claimed_quantity = claimed_quantity - ? WHERE id = ? AND claimed_quantity >= ?",
                    item.getQuantity(), item.getOrderItem().getId(), item.getQuantity());
        }
    }

    /** 출고 전 취소: 결제 전이거나 결제 완료이면서 운송장이 없을 때만. 주문 행 잠금 아래에서 부른다. */
    private void checkCancellable(Order order) {
        boolean beforeShipment = order.getStatus() == OrderStatus.CREATED
                || (order.getStatus() == OrderStatus.PAID && shipmentRepository.findByOrderId(order.getId()).isEmpty());
        if (!beforeShipment) {
            throw new OrderStateException("order.claimNotAllowed");
        }
    }

    /** 반품: 배송 완료 후 7일 안. 주문 상태는 배송 완료(부분 반품으로 일부가 이미 돌아간 경우도 상태는 그대로다). */
    private void checkReturnable(Order order) {
        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new OrderStateException("order.claimNotAllowed");
        }
        LocalDateTime deliveredAt = shipmentRepository.findByOrderId(order.getId())
                .map(s -> s.getDeliveredAt()).orElse(null);
        if (deliveredAt == null || deliveredAt.isBefore(LocalDateTime.now().minusDays(RETURN_WINDOW_DAYS))) {
            throw new OrderStateException("order.returnWindowClosed");
        }
    }

    /** 남은 수량 안에서만 잡는다. 조건부 UPDATE 라 같은 품목을 동시에 두 번 신청해도 초과하지 않는다(V12 CHECK 가 최종 방어). */
    private void reserve(Long orderId, Long orderItemId, int quantity) {
        int updated = jdbc.update("""
                UPDATE order_items SET claimed_quantity = claimed_quantity + ?
                 WHERE id = ? AND order_id = ? AND claimed_quantity + ? <= quantity
                """, quantity, orderItemId, orderId, quantity);
        if (updated == 0) {
            throw new OrderStateException("order.claimQuantityExceeded");
        }
    }

    static BigDecimal itemAmount(OrderClaim claim) {
        return claim.getItems().stream()
                .map(i -> i.getOrderItem().getPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
