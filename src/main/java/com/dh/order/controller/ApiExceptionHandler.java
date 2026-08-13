package com.dh.order.controller;

import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.dh.order.config.Messages;
import com.dh.order.service.OrderStateException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private final Messages messages;

    public ApiExceptionHandler(Messages messages) {
        this.messages = messages;
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<String> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    /**
     * 고객에게 보이는 주문 상태 오류 — 요청 로케일로 해석해서 내려준다.
     * IllegalStateException 핸들러보다 구체적이므로 Spring이 이쪽을 먼저 고른다.
     */
    @ExceptionHandler(OrderStateException.class)
    public ResponseEntity<String> handleOrderState(OrderStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(messages.get(e.getMessageKey(), e.getMessageArgs()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }
}
