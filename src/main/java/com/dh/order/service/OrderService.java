package com.dh.order.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.order.domain.Order;
import com.dh.order.domain.OrderItem;
import com.dh.order.domain.OrderStatus;
import com.dh.order.dto.OrderDtos.OrderCreateRequest;
import com.dh.order.dto.OrderDtos.OrderItemRequest;
import com.dh.order.dto.OrderDtos.OrderItemResponse;
import com.dh.order.dto.OrderDtos.OrderResponse;
import com.dh.order.dto.OrderDtos.OrderAdminSummaryResponse;
import com.dh.order.dto.OrderDtos.OrderSummaryResponse;
import com.dh.order.repository.OrderRepository;

@Service
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional
    public OrderResponse createOrder(OrderCreateRequest request, String customerEmail) {
        Order order = new Order();
        order.setCustomerEmail(isBlank(customerEmail) ? null : customerEmail);
        order.setOrdererName(request.ordererName());
        order.setOrdererPhone(request.ordererPhone());
        order.setShippingAddress(request.shippingAddress());

        BigDecimal total = BigDecimal.ZERO;
        for (OrderItemRequest itemRequest : request.items()) {
            OrderItem item = new OrderItem();
            item.setProductId(itemRequest.productId());
            item.setProductName(itemRequest.productName());
            item.setPrice(itemRequest.price());
            item.setQuantity(itemRequest.quantity());
            order.addItem(item);
            total = total.add(itemRequest.price().multiply(BigDecimal.valueOf(itemRequest.quantity())));
        }
        order.setTotalPrice(total);

        return toResponse(orderRepository.save(order));
    }

    public OrderResponse getOrder(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("order not found: " + id));
        return toResponse(order);
    }

    public List<OrderSummaryResponse> getMyOrders(String customerEmail) {
        return orderRepository.findByCustomerEmailOrderByCreatedAtDesc(customerEmail).stream()
                .map(o -> new OrderSummaryResponse(o.getId(), o.getStatus().name(), o.getTotalPrice(),
                        o.getItems().size(), o.getCreatedAt()))
                .toList();
    }

    public List<OrderAdminSummaryResponse> getAllOrders() {
        return orderRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(o -> new OrderAdminSummaryResponse(o.getId(), o.getCustomerEmail(), o.getOrdererName(),
                        o.getOrdererPhone(), o.getStatus().name(), o.getTotalPrice(), o.getItems().size(),
                        o.getCreatedAt()))
                .toList();
    }

    // 실제 PG 연동 전까지의 mock 결제 - 항상 성공 처리. 나중에 여기만 실제 PG 클라이언트 호출로 교체하면 됨.
    @Transactional
    public OrderResponse payOrder(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("order not found: " + id));
        if (order.getStatus() != OrderStatus.CREATED) {
            throw new IllegalStateException("이미 결제 처리된 주문입니다: " + id);
        }
        order.setStatus(OrderStatus.PAID);
        order.setPaidAt(LocalDateTime.now());
        return toResponse(order);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(i -> new OrderItemResponse(i.getProductId(), i.getProductName(), i.getPrice(), i.getQuantity()))
                .toList();

        return new OrderResponse(
                order.getId(),
                order.getOrdererName(),
                order.getOrdererPhone(),
                order.getShippingAddress(),
                order.getStatus().name(),
                order.getTotalPrice(),
                items,
                order.getCreatedAt(),
                order.getPaidAt());
    }
}
