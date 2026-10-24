package com.dh.order.repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.dh.order.domain.Order;
import com.dh.order.domain.OrderStatus;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

/**
 * 관리자 주문 검색 조건(gateway#329). 비어 있는 조건은 걸지 않는다 — 전부 비면 전체 주문이다.
 *
 * <p>JPQL 의 {@code :param is null or ...} 로 쓰지 않고 Criteria 로 조립한다. Postgres 는 null 문자열 파라미터의
 * 타입을 정하지 못해 {@code could not determine data type of parameter} 로 죽는다 — 조건이 늘수록 그런 분기도 는다.
 *
 * @param status 주문 상태. null 이면 전부
 * @param from   주문 시각 하한(포함, UTC). null 이면 제한 없음
 * @param to     주문 시각 상한(제외, UTC). null 이면 제한 없음
 * @param keyword 주문번호(숫자 그대로 일치)·주문자 이름·연락처(숫자·하이픈만인 검색어, 숫자만 비교)·회원 이메일·비회원 연락 이메일 중 하나에 걸리면 된다
 */
public record AdminOrderSearch(OrderStatus status, LocalDateTime from, LocalDateTime to, String keyword) {

    /** 주문번호로 볼 수 있는 최대 자릿수 — 넘으면 Long 파싱이 터진다. */
    private static final int MAX_ID_DIGITS = 18;

    public Specification<Order> toSpecification() {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (status != null) {
                where.add(cb.equal(root.get("status"), status));
            }
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                where.add(cb.lessThan(root.get("createdAt"), to));
            }
            String text = keyword == null ? "" : keyword.trim();
            if (!text.isEmpty()) {
                String like = "%" + escapeLike(text.toLowerCase(Locale.ROOT)) + "%";
                List<Predicate> any = new ArrayList<>();
                any.add(cb.like(cb.lower(root.get("ordererName")), like, '\\'));
                any.add(cb.like(cb.lower(root.get("customerEmail")), like, '\\'));
                any.add(cb.like(cb.lower(root.get("guestEmail")), like, '\\'));
                // 숫자·하이픈·공백만으로 된 검색어만 연락처로 본다 — "100%" 같은 이름 검색에서 숫자만 뽑아 연락처에 걸면 엉뚱한 주문이 딸려 온다.
                if (text.matches("[0-9][0-9 -]*")) {
                    String digits = text.replaceAll("[^0-9]", "");
                    // 연락처는 "010-1234-5678" 과 "01012345678" 이 섞여 저장돼 있다 — 양쪽 다 하이픈·공백을 빼고 비교한다.
                    Expression<String> phone = cb.function("replace", String.class,
                            cb.function("replace", String.class, root.get("ordererPhone"), cb.literal("-"), cb.literal("")),
                            cb.literal(" "), cb.literal(""));
                    any.add(cb.like(phone, "%" + digits + "%"));
                }
                if (text.matches("#?[0-9]{1," + MAX_ID_DIGITS + "}")) {
                    any.add(cb.equal(root.get("id"), Long.parseLong(text.replace("#", ""))));
                }
                where.add(cb.or(any.toArray(Predicate[]::new)));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
