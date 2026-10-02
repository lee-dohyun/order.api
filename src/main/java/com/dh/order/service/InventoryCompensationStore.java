package com.dh.order.service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code inventory_compensations}(V8) 의 상태 전이. 전이는 전부 <b>조건부 UPDATE 한 문장</b>이다 —
 * "읽고 판단하고 쓰기"로 나누면 결제 요청과 스케줄러가 같은 행을 동시에 집을 수 있다. 영향받은 행 수가
 * 곧 "내가 이 전이를 얻었는가"의 답이다.
 *
 * <p>쓰기 경로라서 클래스 레벨 {@code readOnly} 인 {@link OrderService} 에 두지 않고 별도 빈으로 뺐다.
 */
@Component
public class InventoryCompensationStore {

    public static final String PAYMENT = "PAYMENT";
    public static final String REFUND = "REFUND";

    /** 스케줄러와 즉시 처리 경로가 넘겨받는 미결 행. */
    public record Task(Long id, Long orderId, String kind, int attempts) {
    }

    private final JdbcTemplate jdbc;

    public InventoryCompensationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 결제 시도를 기록한다. <b>재고 차감 요청을 보내기 전에</b> 불러야 한다 — 차감 뒤에 남기면 그 사이에
     * 죽었을 때 흔적이 없다.
     *
     * @return 복원이 진행 중({@code RESTORING})이라 지금은 결제를 받을 수 없으면 false
     */
    @Transactional
    public boolean beginPayment(Long orderId) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        return jdbc.update("""
                INSERT INTO inventory_compensations (order_id, kind, status, touched_at, created_at)
                VALUES (?, 'PAYMENT', 'WATCHING', ?, ?)
                ON CONFLICT (order_id, kind) DO UPDATE
                   SET status = 'WATCHING', touched_at = EXCLUDED.touched_at, completed_at = NULL
                 WHERE inventory_compensations.status <> 'RESTORING'
                """, orderId, now, now) == 1;
    }

    /**
     * 결제 확정. {@link OrderPaymentFinalizer#markPaid} 의 트랜잭션에 합류해 PAID 전이와 함께 커밋된다.
     *
     * @return 행이 {@code WATCHING} 이 아니면 false — 스케줄러가 이 시도를 죽은 것으로 보고 복원을
     *         시작했다는 뜻이므로 결제를 확정하면 안 된다
     */
    @Transactional
    public boolean settlePayment(Long orderId) {
        return jdbc.update("""
                UPDATE inventory_compensations
                   SET status = 'DONE', completed_at = ?
                 WHERE order_id = ? AND kind = 'PAYMENT' AND status = 'WATCHING'
                """, Timestamp.valueOf(LocalDateTime.now()), orderId) == 1;
    }

    /** 환불의 재고 복원 예약. {@code refundOrder} 의 트랜잭션에 합류해 REFUNDED 전이와 함께 커밋된다. */
    @Transactional
    public void recordRefund(Long orderId) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("""
                INSERT INTO inventory_compensations (order_id, kind, status, touched_at, created_at)
                VALUES (?, 'REFUND', 'RESTORING', ?, ?)
                ON CONFLICT (order_id, kind) DO NOTHING
                """, orderId, now, now);
    }

    /** 결제 확정이 실패한 직후, 그 시도의 행을 복원 대상으로 바꾼다. 얻지 못하면 비어 있다. */
    @Transactional
    public List<Task> claimPaymentForRestore(Long orderId) {
        return jdbc.query("""
                UPDATE inventory_compensations
                   SET status = 'RESTORING', touched_at = ?
                 WHERE order_id = ? AND kind = 'PAYMENT' AND status = 'WATCHING'
                RETURNING id, order_id, kind, attempts
                """, InventoryCompensationStore::toTask, Timestamp.valueOf(LocalDateTime.now()), orderId);
    }

    /** 환불 직후 즉시 처리용. 스케줄러가 같은 행을 동시에 집지 않게 {@code touched_at} 을 민다. */
    @Transactional
    public List<Task> claimRefundForRestore(Long orderId) {
        return jdbc.query("""
                UPDATE inventory_compensations
                   SET touched_at = ?
                 WHERE order_id = ? AND kind = 'REFUND' AND status = 'RESTORING'
                RETURNING id, order_id, kind, attempts
                """, InventoryCompensationStore::toTask, Timestamp.valueOf(LocalDateTime.now()), orderId);
    }

    /**
     * 스케줄러가 처리할 행을 집는다. 방치된 {@code WATCHING}(시도가 중간에 죽음)과 재시도 간격이 지난
     * {@code RESTORING} 을 {@code RESTORING} 으로 바꾸며 {@code touched_at} 을 민다. 조건부 UPDATE 라서
     * 파드가 여러 개여도 한 행은 한 쪽만 얻는다.
     *
     * <p>재시도 간격은 실패할수록 늘어난다({@code retryDelay × 2^attempts}, 상한 {@code maxRetryDelay}).
     */
    @Transactional
    public List<Task> claimDue(Duration watchTimeout, Duration retryDelay, Duration maxRetryDelay, int limit) {
        LocalDateTime now = LocalDateTime.now();
        return jdbc.query("""
                UPDATE inventory_compensations
                   SET status = 'RESTORING', touched_at = ?
                 WHERE id IN (
                        SELECT id FROM inventory_compensations
                         WHERE (status = 'WATCHING' AND touched_at < ?)
                            OR (status = 'RESTORING'
                                AND touched_at + LEAST(? * power(2, LEAST(attempts, 20)), ?) * interval '1 second' < ?)
                         ORDER BY touched_at
                         LIMIT ?
                           FOR UPDATE SKIP LOCKED)
                RETURNING id, order_id, kind, attempts
                """, InventoryCompensationStore::toTask,
                Timestamp.valueOf(now),
                Timestamp.valueOf(now.minus(watchTimeout)),
                (double) retryDelay.toSeconds(), (double) maxRetryDelay.toSeconds(),
                Timestamp.valueOf(now),
                limit);
    }

    @Transactional
    public void markDone(Long id) {
        jdbc.update("""
                UPDATE inventory_compensations SET status = 'DONE', completed_at = ?, last_error = NULL WHERE id = ?
                """, Timestamp.valueOf(LocalDateTime.now()), id);
    }

    /** 복원 실패. {@code RESTORING} 으로 남아 다음 주기에 다시 집힌다. */
    @Transactional
    public void markFailed(Long id, String error) {
        String trimmed = error == null ? null : error.substring(0, Math.min(error.length(), 500));
        jdbc.update("""
                UPDATE inventory_compensations
                   SET attempts = attempts + 1, last_error = ?, touched_at = ?
                 WHERE id = ?
                """, trimmed, Timestamp.valueOf(LocalDateTime.now()), id);
    }

    private static Task toTask(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Task(rs.getLong("id"), rs.getLong("order_id"), rs.getString("kind"), rs.getInt("attempts"));
    }
}
