package com.dh.order.event;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String aggregateType; // 예: "Order"

    @Column(nullable = false, length = 100)
    private String aggregateId;

    @Column(nullable = false, length = 100)
    private String type; // 예: "OrderCreatedEvent"

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private boolean processed = false;

    // JPA 기본 생성자
    protected OutboxEvent() {}

    public OutboxEvent(String aggregateType, String aggregateId, String type, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.type = type;
        this.payload = payload;
        this.createdAt = LocalDateTime.now();
        this.processed = false;
    }

    public void markAsProcessed() {
        this.processed = true;
    }

    public Long getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getType() { return type; }
    public String getPayload() { return payload; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public boolean isProcessed() { return processed; }
}
