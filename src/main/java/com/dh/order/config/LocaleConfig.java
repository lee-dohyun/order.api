package com.dh.order.config;

import java.util.List;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.LocaleResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 다국어(ko/en/zh/ja) 응답을 위한 로케일 결정 + 메시지 소스 연결.
 *
 * <p>메시지 자체는 {@code messages*.properties}에 있고 MessageSource 빈은 Spring Boot
 * 자동설정(application.yml의 {@code spring.messages.*})이 만든다. 여기서 하는 일은 두 가지다:
 * <ul>
 *   <li>요청마다 로케일을 정해 {@code LocaleContextHolder}에 실어주는 LocaleResolver</li>
 *   <li>Bean Validation의 {@code message = "{key}"}가 ValidationMessages가 아니라 우리
 *       messages 번들에서 해석되도록 Validator에 MessageSource를 물려주는 것</li>
 * </ul>
 */
@Configuration
public class LocaleConfig {

    /** 지원 로케일. 이 목록에 없는 언어로 요청이 오면 {@link #DEFAULT_LOCALE}로 응답한다. */
    static final List<Locale> SUPPORTED_LOCALES =
            List.of(Locale.KOREAN, Locale.ENGLISH, Locale.CHINESE, Locale.JAPANESE);

    static final Locale DEFAULT_LOCALE = Locale.KOREAN;

    /** 게이트웨이가 경로 프리픽스(/ko, /en ...)에서 뽑아 전파해주는 헤더. */
    static final String LOCALE_HEADER = "X-Locale";

    @Bean
    public LocaleResolver localeResolver() {
        return new HeaderLocaleResolver();
    }

    /**
     * Spring Boot 기본 Validator를 대체한다. {@code setValidationMessageSource}를 호출해야만
     * DTO 애노테이션의 {@code {key}}가 messages 번들에서 해석되고, 그때 쓰이는 로케일은
     * LocaleResolver가 세팅한 LocaleContextHolder 값이 된다.
     */
    @Bean
    public LocalValidatorFactoryBean defaultValidator(MessageSource messageSource) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setValidationMessageSource(messageSource);
        return validator;
    }

    /**
     * X-Locale 헤더를 우선 보고, 없으면 Accept-Language로 협상한다.
     *
     * <p>게이트웨이의 X-Locale 전파는 프론트 경로 프리픽스 라우팅(#103)이 붙어야 의미가 생기므로,
     * 그전까지는 브라우저가 보내는 Accept-Language로 동작한다 — 두 저장소가 서로를 기다리지
     * 않도록 일부러 이 순서로 뒀다.
     */
    private static class HeaderLocaleResolver implements LocaleResolver {

        @Override
        public Locale resolveLocale(HttpServletRequest request) {
            Locale fromHeader = lookup(request.getHeader(LOCALE_HEADER));
            if (fromHeader != null) {
                return fromHeader;
            }
            Locale fromAcceptLanguage = lookup(request.getHeader("Accept-Language"));
            return fromAcceptLanguage != null ? fromAcceptLanguage : DEFAULT_LOCALE;
        }

        @Override
        public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
            throw new UnsupportedOperationException("로케일은 요청 헤더로만 결정된다.");
        }

        /** zh-CN/en-US처럼 지역이 붙어 와도 언어 코드까지 잘라서 매칭한다. 못 찾으면 null. */
        private Locale lookup(String languageRanges) {
            if (languageRanges == null || languageRanges.isBlank()) {
                return null;
            }
            try {
                return Locale.lookup(Locale.LanguageRange.parse(languageRanges), SUPPORTED_LOCALES);
            } catch (IllegalArgumentException e) {
                // 형식이 깨진 헤더 — 기본 로케일로 떨어뜨린다
                return null;
            }
        }
    }
}
