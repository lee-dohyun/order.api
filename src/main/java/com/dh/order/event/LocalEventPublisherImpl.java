package com.dh.order.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * EventPublisher의 임시 구현체.
 * K3s 단일 노드 환경 제약으로 실제 NATS/Kafka 배포 전까지
 * Spring ApplicationEvent와 로그를 이용해 EDA 흐름을 시뮬레이션합니다.
 */
@Component
public class LocalEventPublisherImpl implements EventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(LocalEventPublisherImpl.class);
    private final ApplicationEventPublisher springPublisher;

    public LocalEventPublisherImpl(ApplicationEventPublisher springPublisher) {
        this.springPublisher = springPublisher;
    }

    @Override
    public void publish(String topic, String payload) {
        // 향후 NATS JetStream 등으로 교체될 지점
        logger.info("[EDA] Publishing event to topic '{}': {}", topic, payload);
        
        // 내부 버스로 임시 발행 (필요 시 내부 리스너가 받아서 처리)
        springPublisher.publishEvent(new InternalEvent(topic, payload));
    }

    public static class InternalEvent {
        private final String topic;
        private final String payload;

        public InternalEvent(String topic, String payload) {
            this.topic = topic;
            this.payload = payload;
        }

        public String getTopic() { return topic; }
        public String getPayload() { return payload; }
    }
}
