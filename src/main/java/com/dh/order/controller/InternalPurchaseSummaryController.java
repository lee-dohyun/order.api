package com.dh.order.controller;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dh.order.repository.OrderRepository;

/**
 * auth.api 의 회원 등급 산정 배치가 클러스터 내부망으로만 호출한다.
 *
 * <p>{@code /internal/**} 는 게이트웨이에 라우트가 없어 외부에서 도달 불가능하다
 * (product.api {@code InternalVariantController} 와 같은 신뢰 경계).
 *
 * <p><b>등급의 근거가 되는 금액은 여기서만 나온다.</b> 주문 데이터를 가진 쪽이 집계해서
 * 넘겨야지, auth.api 가 주문 DB 를 직접 읽게 하면 두 서비스가 같은 테이블에 묶인다.
 */
@RestController
@RequestMapping("/internal/purchase-summary")
public class InternalPurchaseSummaryController {

    private final OrderRepository orderRepository;

    public InternalPurchaseSummaryController(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public record PurchaseSummaryResponse(String customerId, BigDecimal confirmedAmount) {
    }

    /**
     * @param since 이 시각 이후 생성된 주문만 집계한다. 등급 기준 기간(예: 최근 6개월)을
     *              호출하는 쪽이 정하게 두어, 기준이 바뀌어도 이 API 는 안 바뀐다.
     */
    @GetMapping
    public List<PurchaseSummaryResponse> summarize(
            @RequestParam("since") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since) {
        return orderRepository.sumConfirmedPurchasesSince(since).stream()
                .map(row -> new PurchaseSummaryResponse(row.getCustomerId(), row.getConfirmedAmount()))
                .toList();
    }

    /**
     * 회원 한 명의 구매확정액. auth.api 가 마이페이지의 "다음 등급까지 남은 금액"을 계산할 때 부른다
     * (gateway#81). 구매확정이 없으면 0 으로 응답한다 — 전체 집계와 달리 대상이 정해져 있어 "없음"을
     * 누락으로 표현할 이유가 없다.
     */
    @GetMapping("/{customerId}")
    public PurchaseSummaryResponse summarizeOne(
            @PathVariable String customerId,
            @RequestParam("since") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since) {
        return new PurchaseSummaryResponse(customerId, orderRepository.sumConfirmedPurchasesOf(customerId, since));
    }
}
