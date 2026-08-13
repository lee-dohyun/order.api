package com.dh.order.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 번역 누락은 컴파일도 런타임도 안 잡아준다 — 키가 빠진 로케일로 요청이 들어와야
 * 그제서야 기본 번들(한국어)이 튀어나온다. 그래서 여기서 키 집합을 고정한다.
 */
class MessageBundleTest {

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh", "ja"})
    void 번역_번들은_기본_번들과_키_집합이_같다(String language) throws IOException {
        Set<String> baseKeys = load("messages.properties").stringPropertyNames();
        Set<String> translatedKeys = load("messages_" + language + ".properties").stringPropertyNames();

        assertThat(translatedKeys)
                .as("messages_%s.properties에 빠진 키", language)
                .containsExactlyInAnyOrderElementsOf(baseKeys);
    }

    private Properties load(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("%s를 찾을 수 없음", resource).isNotNull();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
