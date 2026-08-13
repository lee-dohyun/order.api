package com.dh.order.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.order.config.ProductApiClient;
import com.dh.order.domain.Order;
import com.dh.order.domain.OrderItem;
import com.dh.order.domain.OrderStatus;
import com.dh.order.domain.Shipment;
import com.dh.order.domain.ShipmentStatus;
import com.dh.order.dto.OrderDtos.CreateShipmentRequest;
import com.dh.order.dto.OrderDtos.OrderCreateRequest;
import com.dh.order.dto.OrderDtos.OrderItemRequest;
import com.dh.order.dto.OrderDtos.OrderItemResponse;
import com.dh.order.dto.OrderDtos.OrderResponse;
import com.dh.order.dto.OrderDtos.OrderAdminSummaryResponse;
import com.dh.order.dto.OrderDtos.OrderSummaryResponse;
import com.dh.order.dto.OrderDtos.ShipmentResponse;
import com.dh.order.repository.OrderRepository;
import com.dh.order.repository.ShipmentRepository;

@Service
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final ShipmentRepository shipmentRepository;
    private final OrderNotificationService notificationService;
    private final ProductApiClient productApiClient;

    public OrderService(
            OrderRepository orderRepository,
            ShipmentRepository shipmentRepository,
            OrderNotificationService notificationService,
            ProductApiClient productApiClient) {
        this.orderRepository = orderRepository;
        this.shipmentRepository = shipmentRepository;
        this.notificationService = notificationService;
        this.productApiClient = productApiClient;
    }

    @Transactional
    public OrderResponse createOrder(OrderCreateRequest request, String customerEmail) {
        Order order = new Order();
        order.setCustomerEmail(isBlank(customerEmail) ? null : customerEmail);
        order.setOrdererName(request.ordererName());
        order.setOrdererPhone(request.ordererPhone());
        order.setShippingAddress(request.shippingAddress());
        order.setRecipientName(request.recipientName());
        order.setRecipientPhone(request.recipientPhone());
        order.setZipCode(request.zipCode());
        order.setAddress1(request.address1());
        order.setAddress2(request.address2());

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
        // 재고 차감 실패(부족/product.api 오류) 시 예외가 트랜잭션을 롤백시켜 결제 확정 전으로 되돌린다.
        productApiClient.deductInventory(order.getId(), order.getItems());
        order.setStatus(OrderStatus.PAID);
        order.setPaidAt(LocalDateTime.now());
        notificationService.notifyPaid(order);
        return toResponse(order);
    }

    /** admin이 운송장을 등록 - PAID 주문만 가능, 등록 즉시 SHIPPED로 전이한다. */
    @Transactional
    public ShipmentResponse createShipment(Long orderId, CreateShipmentRequest request) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("order not found: " + orderId));
        if (order.getStatus() != OrderStatus.PAID) {
            throw new IllegalStateException("결제 완료 상태의 주문만 배송을 등록할 수 있습니다: " + orderId);
        }
        if (shipmentRepository.findByOrderId(orderId).isPresent()) {
            throw new IllegalStateException("이미 배송이 등록된 주문입니다: " + orderId);
        }

        Shipment shipment = new Shipment(order, request.carrier(), request.trackingNumber());
        shipmentRepository.save(shipment);
        order.setStatus(OrderStatus.SHIPPED);
        return toShipmentResponse(shipment);
    }

    /** admin이 배송 완료 처리 - SHIPPED 상태만 가능. */
    @Transactional
    public ShipmentResponse markDelivered(Long orderId) {
        Shipment shipment = shipmentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new NoSuchElementException("shipment not found for order: " + orderId));
        if (shipment.getStatus() != ShipmentStatus.SHIPPED) {
            throw new IllegalStateException("배송중 상태의 주문만 배송완료로 처리할 수 있습니다: " + orderId);
        }
        shipment.markDelivered();
        shipment.getOrder().setStatus(OrderStatus.DELIVERED);
        return toShipmentResponse(shipment);
    }

    public ShipmentResponse getShipment(Long orderId) {
        Shipment shipment = shipmentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new NoSuchElementException("shipment not found for order: " + orderId));
        return toShipmentResponse(shipment);
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
                order.getRecipientName(),
                order.getRecipientPhone(),
                order.getZipCode(),
                order.getAddress1(),
                order.getAddress2(),
                order.getStatus().name(),
                order.getTotalPrice(),
                items,
                order.getCreatedAt(),
                order.getPaidAt());
    }

    private ShipmentResponse toShipmentResponse(Shipment shipment) {
        return new ShipmentResponse(
                shipment.getOrder().getId(),
                shipment.getCarrier(),
                shipment.getTrackingNumber(),
                shipment.getStatus().name(),
                shipment.getShippedAt(),
                shipment.getDeliveredAt());
    }
}
