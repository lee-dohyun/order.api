package com.dh.order.config;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

/**
 * 금액을 로케일에 맞는 통화 표기로 바꾼다. 통화 기호뿐 아니라 자릿수 구분자와 소수점
 * 자릿수(KRW/JPY는 0, USD/CNY는 2)까지 달라지므로 직접 문자열을 붙이지 말고 여기를 쓸 것.
 *
 * <p>같은 KRW라도 보는 사람의 언어에 따라 표기가 달라진다:
 * ko/en {@code ₩128,000}, zh {@code ￦128,000}, ja {@code ₩128,000}.
 *
 * <p>지금은 주문 금액이 KRW로만 저장되므로 통화 변환은 하지 않는다 — 환율 변환은 #108의
 * 몫이고, 그때 변환된 금액과 목표 통화를 그대로 여기에 넘기면 된다.
 */
public final class MoneyFormatter {

    private MoneyFormatter() {
    }

    public static String format(BigDecimal amount, Currency currency, Locale locale) {
        NumberFormat format = NumberFormat.getCurrencyInstance(locale);
        format.setCurrency(currency);
        // 통화별 기본 소수 자릿수를 명시적으로 강제한다 — 로케일 기본 포맷이 KRW에도
        // 소수점 두 자리를 붙이는 경우가 있어서(₩128,000.00) 통화 쪽 정의를 우선시킨다.
        int fractionDigits = currency.getDefaultFractionDigits();
        format.setMinimumFractionDigits(fractionDigits);
        format.setMaximumFractionDigits(fractionDigits);
        return format.format(amount);
    }
}
