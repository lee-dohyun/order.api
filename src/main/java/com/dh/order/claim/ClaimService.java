package com.dh.order.claim;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.dh.order.claim.ClaimDtos.ClaimCreateRequest;
import com.dh.order.claim.ClaimDtos.ClaimResponse;
import com.dh.order.config.ProductApiClient;
import com.dh.order.config.ProductApiClient.ShippingQuoteItem;
import com.dh.order.domain.Order;
import com.dh.order.domain.OrderItem;
import com.dh.order.dto.OrderDtos.Requester;
import com.dh.order.payment.PaymentRepository;
import com.dh.order.repository.OrderRepository;
import com.dh.order.service.InventoryCompensator;
import com.dh.order.service.OrderStateException;

/**
 * 고객 상품별 취소·반품의 흐름(gateway#320). 트랜잭션을 두지 않는다 — 금액 계산에 product.api 배송비 견적(원격
 * HTTP)이 필요해서, 계산은 트랜잭션 밖에서 하고 확정은 {@link ClaimCommitter} 가 주문 행 잠금 아래에서 한다.
 * 확정 뒤 재고 부분 복원도 커밋 밖에서 부르고, 실패하면 예약 행이 남아 스케줄러가 이어받는다.
 */
@Service
public class ClaimService {

    private static final Logger log = LoggerFactory.getLogger(ClaimService.class);
    private static final int MAX_COMPLETE_ATTEMPTS = 3;

    private final ClaimCommitter committer;
    private final OrderClaimRepository claimRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final ProductApiClient productApiClient;
    private final InventoryCompensator compensator;

    public ClaimService(ClaimCommitter committer, OrderClaimRepository claimRepository, OrderRepository orderRepository,
            PaymentRepository paymentRepository, ProductApiClient productApiClient, InventoryCompensator compensator) {
        this.committer = committer;
        this.claimRepository = claimRepository;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.productApiClient = productApiClient;
        this.compensator = compensator;
    }

    /**
     * 고객 신청. 출고 전 취소는 그 자리에서 환불까지 끝나고, 반품은 관리자 승인을 기다린다. 같은 멱등 키의 재시도는
     * 새 클레임을 만들지 않고 처음 것을 돌려준다(남의 주문 키면 404).
     */
    public ClaimResponse request(Long orderId, Requester requester, ClaimCreateRequest request, String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<ClaimResponse> replay = replay(orderId, idempotencyKey);
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        ClaimCommitter.Created created;
        try {
            created = committer.create(orderId, requester, request, idempotencyKey);
        } catch (DataIntegrityViolationException e) {
            // 같은 키의 동시 재시도가 유니크 인덱스(V12)에 진 경우.
            if (idempotencyKey == null) {
                throw e;
            }
            return replay(orderId, idempotencyKey).orElseThrow(() -> e);
        }
        if (request.type() == ClaimType.CANCEL && !created.completed()) {
            complete(created.claimId(), null);
        }
        return get(created.claimId());
    }

    /** 관리자 승인 — 사유를 바꿀 수 있다. 환불·재고 복원까지 끝낸다. */
    public ClaimResponse approve(Long claimId, ClaimReason overrideReason) {
        complete(claimId, overrideReason);
        return get(claimId);
    }

    public ClaimResponse reject(Long claimId, String reason) {
        committer.reject(claimId, reason);
        return get(claimId);
    }

    public List<ClaimResponse> listByStatus(ClaimStatus status) {
        return claimRepository.findByStatusWithOrder(status).stream()
                .map(c -> claimRepository.findByIdWithItems(c.getId()).orElseThrow())
                .map(ClaimResponse::of)
                .toList();
    }

    public List<ClaimResponse> claimsOf(Long orderId) {
        return claimRepository.findByOrderIdWithItems(orderId).stream().map(ClaimResponse::of).toList();
    }

    private ClaimResponse get(Long claimId) {
        return ClaimResponse.of(claimRepository.findByIdWithItems(claimId).orElseThrow());
    }

    private Optional<ClaimResponse> replay(Long orderId, String idempotencyKey) {
        return claimRepository.findByIdempotencyKey(idempotencyKey)
                .map(c -> {
                    if (!c.getOrder().getId().equals(orderId)) {
                        throw new NoSuchElementException("order not found: " + orderId);
                    }
                    return get(c.getId());
                });
    }

    /**
     * 금액을 계산해 확정하고(다른 클레임이 그 사이 끝났으면 다시 계산), 커밋 뒤 재고 부분 복원을 부른다.
     */
    private void complete(Long claimId, ClaimReason overrideReason) {
        for (int attempt = 1; attempt <= MAX_COMPLETE_ATTEMPTS; attempt++) {
            OrderClaim claim = claimRepository.findByIdWithItems(claimId)
                    .orElseThrow(() -> new NoSuchElementException("claim not found: " + claimId));
            if (claim.getStatus() != ClaimStatus.REQUESTED) {
                throw new OrderStateException("order.claimAlreadyDecided");
            }
            ClaimReason reason = overrideReason != null ? overrideReason : claim.getReasonType();
            List<OrderClaim> completed = claimRepository.findByOrderIdWithItems(claim.getOrder().getId()).stream()
                    .filter(c -> c.getStatus() == ClaimStatus.COMPLETED).toList();
            ClaimRefundCalculator.Result amounts = calculate(claim, reason, completed);
            if (committer.complete(claimId, reason, completed.size(), amounts)) {
                compensator.restoreClaimNow(claimId);
                return;
            }
            log.info("다른 클레임이 먼저 끝나 금액을 다시 계산한다 (claimId={}, attempt={})", claimId, attempt);
        }
        throw new OrderStateException("order.claimNotAllowed");
    }

    ClaimRefundCalculator.Result calculate(OrderClaim claim, ClaimReason reason, List<OrderClaim> completed) {
        Order order = orderRepository.findByIdWithItems(claim.getOrder().getId()).orElseThrow();
        boolean customerCaused = claim.getType() == ClaimType.CANCEL || reason == ClaimReason.CHANGE_OF_MIND;

        // 품목별로 이번 클레임까지 반영한 뒤 남는 수량. 진행 중(REQUESTED)인 다른 클레임은 아직 남은 것으로 본다.
        Map<Long, Integer> returned = new HashMap<>();
        for (OrderClaim c : completed) {
            c.getItems().forEach(i -> returned.merge(i.getOrderItem().getId(), i.getQuantity(), Integer::sum));
        }
        claim.getItems().forEach(i -> returned.merge(i.getOrderItem().getId(), i.getQuantity(), Integer::sum));

        BigDecimal originalSubtotal = BigDecimal.ZERO;
        BigDecimal remainingSubtotal = BigDecimal.ZERO;
        List<ShippingQuoteItem> remainingLines = new ArrayList<>();
        boolean offersKnown = true;
        for (OrderItem item : order.getItems()) {
            originalSubtotal = originalSubtotal.add(item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            int left = item.getQuantity() - returned.getOrDefault(item.getId(), 0);
            if (left > 0) {
                remainingSubtotal = remainingSubtotal.add(item.getPrice().multiply(BigDecimal.valueOf(left)));
                remainingLines.add(new ShippingQuoteItem(item.getOfferId(), left, item.getPrice()));
            }
            offersKnown &= item.getOfferId() != null;
        }

        // 오퍼 id 가 없는 옛 주문(order.api#14 이전)은 견적을 받을 수 없다 — 배송비 재계산·반품 배송비를 0 으로 둔다.
        // 그런 주문은 배송비 도입(V11) 전이라 배송비 자체가 0 이었다.
        BigDecimal shippingAfter = BigDecimal.ZERO;
        if (customerCaused && offersKnown && !remainingLines.isEmpty()) {
            shippingAfter = productApiClient.quoteShipping(remainingLines).shippingFee();
        }
        BigDecimal returnFee = BigDecimal.ZERO;
        if (claim.getType() == ClaimType.RETURN && reason == ClaimReason.CHANGE_OF_MIND && offersKnown) {
            returnFee = productApiClient.quoteShipping(claim.getItems().stream()
                    .map(i -> new ShippingQuoteItem(i.getOrderItem().getOfferId(), i.getQuantity(), i.getOrderItem().getPrice()))
                    .toList()).returnShippingFee();
        }
        if (!offersKnown) {
            log.info("오퍼 id 없는 옛 주문 - 배송비 재계산·반품 배송비 없이 계산 (orderId={})", order.getId());
        }

        BigDecimal priorShares = sum(completed, OrderClaim::getDiscountShare);
        BigDecimal priorShippingRefund = sum(completed, OrderClaim::getShippingRefund);
        BigDecimal priorRefund = sum(completed, OrderClaim::getRefundAmount);
        BigDecimal paid = paymentRepository.findByOrderId(order.getId())
                .map(p -> p.getAmount()).orElse(BigDecimal.ZERO);

        return ClaimRefundCalculator.calculate(ClaimRefundCalculator.Input.builder()
                .originalSubtotal(originalSubtotal)
                .originalDiscount(order.getDiscountAmount())
                .priorDiscountShares(priorShares)
                .claimItemAmount(ClaimCommitter.itemAmount(claim))
                .remainingSubtotalAfter(remainingSubtotal)
                .shippingChargedBefore(order.getShippingFee().subtract(priorShippingRefund))
                .shippingAfter(shippingAfter)
                .customerCaused(customerCaused)
                .returnShippingFee(returnFee)
                .refundableLeft(paid.subtract(priorRefund))
                .build());
    }

    private static BigDecimal sum(List<OrderClaim> claims, java.util.function.Function<OrderClaim, BigDecimal> f) {
        return claims.stream().map(f).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
