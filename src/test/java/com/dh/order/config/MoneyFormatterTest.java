package com.dh.order.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;

import org.junit.jupiter.api.Test;

class MoneyFormatterTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency JPY = Currency.getInstance("JPY");

    @Test
    void 같은_금액도_보는_언어에_따라_표기가_달라진다() {
        BigDecimal amount = new BigDecimal("128000");

        assertThat(MoneyFormatter.format(amount, KRW, Locale.KOREAN)).isEqualTo("₩128,000");
        assertThat(MoneyFormatter.format(amount, KRW, Locale.ENGLISH)).isEqualTo("₩128,000");
        assertThat(MoneyFormatter.format(amount, KRW, Locale.CHINESE)).isEqualTo("￦128,000");
        assertThat(MoneyFormatter.format(amount, KRW, Locale.JAPANESE)).isEqualTo("₩128,000");
    }

    /** KRW/JPY는 소수점이 없고 USD는 두 자리 — 로케일 기본값이 아니라 통화 정의를 따라야 한다. */
    @Test
    void 통화별_소수_자릿수를_따른다() {
        assertThat(MoneyFormatter.format(new BigDecimal("1500"), KRW, Locale.KOREAN)).isEqualTo("₩1,500");
        assertThat(MoneyFormatter.format(new BigDecimal("1500"), JPY, Locale.JAPANESE)).isEqualTo("￥1,500");
        assertThat(MoneyFormatter.format(new BigDecimal("95.5"), USD, Locale.ENGLISH)).isEqualTo("$95.50");
    }

    /** 자릿수 구분자를 문자열로 직접 붙이던 걸 대체하는 게 이 클래스의 존재 이유다. */
    @Test
    void 자릿수_구분자가_들어간다() {
        assertThat(MoneyFormatter.format(new BigDecimal("1234567"), KRW, Locale.KOREAN)).isEqualTo("₩1,234,567");
    }
}
