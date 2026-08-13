package com.dh.order.config;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * 서비스/컨트롤러에서 현재 요청 로케일로 메시지를 꺼내는 얇은 헬퍼.
 * (DTO 애노테이션의 {@code {key}}는 Validator가 알아서 해석하므로 여기를 거치지 않는다.)
 */
@Component
public class Messages {

    private final MessageSource messageSource;

    public Messages(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public String get(String code, Object... args) {
        return messageSource.getMessage(code, args, LocaleContextHolder.getLocale());
    }
}
