package com.dh.order.service;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.dh.order.config.ProductApiClient;
import com.dh.order.domain.Order;
import com.dh.order.domain.OrderStatus;
import com.dh.order.dto.OrderDtos.OrderItemResponse;
import com.dh.order.repository.OrderRepository;
import com.dh.order.service.InventoryCompensationStore.Task;

/**
 * 재고 복원을 "성공할 때까지" 끌고 가는 쪽(order.api#34).
 *
 * <p>복원 호출(원격 HTTP)은 여기서 트랜잭션 없이 한다. 미결 행을 먼저 커밋해 두고({@link
 * InventoryCompensationStore}) 호출 결과에 따라 행을 닫거나 남긴다 — 호출이 실패하든 그 직후 파드가
 * 죽든 행이 남아 있으므로 다음 주기에 다시 시도된다. 복원은 product.api 쪽에서 주문 ID 기준으로
 * 멱등이라(되돌릴 차감이 없으면 아무것도 하지 않는다) 중복 호출은 안전하다.
 */
@Component
public class InventoryCompensator {

    private static final Logger log = LoggerFactory.getLogger(InventoryCompensator.class);

    private final InventoryCompensationStore store;
    private final OrderRepository orderRepository;
    private final ProductApiClient productApiClient;
    private final Duration watchTimeout;
    private final Duration retryDelay;
    private final Duration maxRetryDelay;
    private final int batchSize;

    public InventoryCompensator(
            InventoryCompensationStore store,
            OrderRepository orderRepository,
            ProductApiClient productApiClient,
            @Value("${app.inventory-compensation.watch-timeout:5m}") Duration watchTimeout,
            @Value("${app.inventory-compensation.retry-delay:1m}") Duration retryDelay,
            @Value("${app.inventory-compensation.max-retry-delay:1h}") Duration maxRetryDelay,
            @Value("${app.inventory-compensation.batch-size:20}") int batchSize) {
        this.store = store;
        this.orderRepository = orderRepository;
        this.productApiClient = productApiClient;
        this.watchTimeout = watchTimeout;
        this.retryDelay = retryDelay;
        this.maxRetryDelay = maxRetryDelay;
        this.batchSize = batchSize;
    }

    /**
     * 결제 확정이 실패한 직후의 보상. 예외를 던지지 않는다 — 여기서 못 끝낸 것은 행이 남아 스케줄러가
     * 이어받는다(행 전이 자체가 실패했다면 {@code WATCHING} 으로 남아 방치 판정으로 집힌다).
     */
    public void compensatePaymentNow(Long orderId) {
        try {
            store.claimPaymentForRestore(orderId).forEach(this::process);
        } catch (RuntimeException e) {
            log.error("결제 보상 복원을 즉시 처리하지 못했다 - 스케줄러가 재시도한다 (orderId={})", orderId, e);
        }
    }

    /** 환불 커밋 직후의 복원. 예외를 던지지 않는다 — 환불 자체는 이미 확정됐다. */
    public void restoreRefundNow(Long orderId) {
        try {
            store.claimRefundForRestore(orderId).forEach(this::process);
        } catch (RuntimeException e) {
            log.error("환불 재고 복원을 즉시 처리하지 못했다 - 스케줄러가 재시도한다 (orderId={})", orderId, e);
        }
    }

    /** 방치된 결제 시도와 실패한 복원을 주기적으로 정리한다. */
    @Scheduled(
            fixedDelayString = "${app.inventory-compensation.poll-interval:60s}",
            initialDelayString = "${app.inventory-compensation.initial-delay:30s}")
    public void sweep() {
        List<Task> due;
        try {
            due = store.claimDue(watchTimeout, retryDelay, maxRetryDelay, batchSize);
        } catch (RuntimeException e) {
            log.error("재고 보상 미결 행 조회 실패 - 다음 주기에 다시 본다", e);
            return;
        }
        due.forEach(this::process);
    }

    private void process(Task task) {
        try {
            Order order = orderRepository.findByIdWithItems(task.orderId()).orElse(null);
            if (order == null) {
                // orders 를 참조하는 FK 가 있어 정상적으로는 올 수 없는 분기다.
                log.error("재고 보상 대상 주문이 없다 (taskId={}, orderId={})", task.id(), task.orderId());
                store.markDone(task.id());
                return;
            }
            if (InventoryCompensationStore.PAYMENT.equals(task.kind()) && isPaymentSettled(order.getStatus())) {
                // 결제는 확정됐는데 행만 열려 있던 경우(동시 결제에서 진 쪽이 남긴 행). 차감은 정당하다.
                store.markDone(task.id());
                return;
            }
            productApiClient.restoreInventory(task.orderId(), toItems(order));
            store.markDone(task.id());
            log.info("재고 복원 완료 (orderId={}, kind={}, 이전 실패 {}회)", task.orderId(), task.kind(), task.attempts());
        } catch (RuntimeException e) {
            log.error("재고 복원 실패 - 재시도 예정 (orderId={}, kind={}, 실패 {}회째)",
                    task.orderId(), task.kind(), task.attempts() + 1, e);
            try {
                store.markFailed(task.id(), e.toString());
            } catch (RuntimeException recordFailure) {
                // 기록을 못 남겨도 행은 RESTORING 으로 남아 있어 다시 집힌다.
                log.error("재고 복원 실패 기록도 실패 (taskId={})", task.id(), recordFailure);
            }
        }
    }

    /** 결제가 확정된 적이 있는 상태. 이 주문들의 차감은 되돌리면 안 된다(환불은 REFUND 행이 따로 처리한다). */
    private static boolean isPaymentSettled(OrderStatus status) {
        return status != OrderStatus.CREATED && status != OrderStatus.CANCELLED;
    }

    private static List<OrderItemResponse> toItems(Order order) {
        return order.getItems().stream()
                .map(i -> new OrderItemResponse(
                        i.getProductId(), i.getVariantId(), i.getProductName(), i.getPrice(), i.getQuantity(),
                        i.getSellerId(), i.getSellerName(), i.getOfferId()))
                .toList();
    }
}
