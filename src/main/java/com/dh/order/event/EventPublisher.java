package com.dh.order.event;

/**
 * Event-Driven Architecture (EDA) 공통 인터페이스.
 * 캐논 규칙에 따라 단일 노드(K3s) 환경에서는 무거운 브로커(Kafka 등)의 실 배포를 유예합니다.
 * 본 인터페이스를 통해 실제 발행기(예: Outbox 엔티티 저장, Spring ApplicationEvent 등)를 
 * 추상화하여 추후 교체 가능성을 확보합니다.
 */
public interface EventPublisher {
    
    /**
     * 이벤트를 발행합니다.
     * @param topic 이벤트의 토픽 (예: "posselect.order.created.v1")
     * @param payload 이벤트의 JSON 직렬화된 본문
     */
    void publish(String topic, String payload);
    
}
