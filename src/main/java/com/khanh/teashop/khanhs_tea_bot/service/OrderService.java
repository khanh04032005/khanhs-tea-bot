package com.khanh.teashop.khanhs_tea_bot.service;

import com.khanh.teashop.khanhs_tea_bot.dto.CreateOrderItemRequest;
import com.khanh.teashop.khanhs_tea_bot.dto.CreateOrderRequest;
import com.khanh.teashop.khanhs_tea_bot.dto.OrderItemResponse;
import com.khanh.teashop.khanhs_tea_bot.dto.OrderResponse;
import com.khanh.teashop.khanhs_tea_bot.entity.Order;
import com.khanh.teashop.khanhs_tea_bot.entity.OrderItem;
import com.khanh.teashop.khanhs_tea_bot.entity.OrderStatus;
import com.khanh.teashop.khanhs_tea_bot.entity.Product;
import com.khanh.teashop.khanhs_tea_bot.repository.OrderRepository;
import com.khanh.teashop.khanhs_tea_bot.repository.ProductRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        Order order = new Order();
        order.setCustomerName(request.getCustomerName().trim());
        order.setCustomerPhone(request.getCustomerPhone().trim());
        order.setAddress(request.getAddress());
        order.setStatus(OrderStatus.PENDING);
        order.setTotalAmount(0);

        int totalAmount = 0;

        for (CreateOrderItemRequest itemRequest : request.getItems()) {
            Product product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Product not found with id: " + itemRequest.getProductId()
                    ));

            if (!product.isAvailable()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Product is not available: " + product.getId()
                );
            }

            String normalizedSize = normalizeSize(itemRequest.getSize());
            int unitPrice = "M".equals(normalizedSize) ? product.getPriceM() : product.getPriceL();
            int quantity = itemRequest.getQuantity();
            int lineTotal = unitPrice * quantity;

            OrderItem orderItem = new OrderItem();
            orderItem.setProduct(product);
            orderItem.setSize(normalizedSize);
            orderItem.setQuantity(quantity);
            orderItem.setUnitPrice(unitPrice);
            orderItem.setLineTotal(lineTotal);

            order.addItem(orderItem);
            totalAmount += lineTotal;
        }
        order.setTelegramChatId(request.getTelegramChatId());
        order.setTotalAmount(totalAmount);
        Order savedOrder = orderRepository.save(order);
        return mapToOrderResponse(savedOrder);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderById(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Order not found with id: " + id
                ));
        return mapToOrderResponse(order);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getAllOrders() {
        List<Order> orders = orderRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
        return orders.stream()
                .map(this::mapToOrderResponse)
                .toList();
    }

    private String normalizeSize(String size) {
        String normalized = size == null ? "" : size.trim().toUpperCase();
        if (!"M".equals(normalized) && !"L".equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Size must be M or L");
        }
        return normalized;
    }

    private OrderResponse mapToOrderResponse(Order order) {
        List<OrderItemResponse> itemResponses = order.getItems().stream()
                .map(this::mapToOrderItemResponse)
                .toList();

        return OrderResponse.builder()
                .id(order.getId())
                .customerName(order.getCustomerName())
                .customerPhone(order.getCustomerPhone())
            .address(order.getAddress())
                .status(order.getStatus().name())
                .totalAmount(order.getTotalAmount())
                .createdAt(order.getCreatedAt())
                .items(itemResponses)
                .build();
    }

    private OrderItemResponse mapToOrderItemResponse(OrderItem item) {
        return OrderItemResponse.builder()
                .id(item.getId())
                .productId(item.getProduct().getId())
                .productName(item.getProduct().getName())
                .size(item.getSize())
                .quantity(item.getQuantity())
                .unitPrice(item.getUnitPrice())
                .lineTotal(item.getLineTotal())
                .build();
    }

    @Transactional
    public OrderResponse cancelOrder(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Order not found with id: " + id
                ));

        if (order.getStatus() == OrderStatus.PAID) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paid order cannot be cancelled");
        }

        order.setStatus(OrderStatus.CANCELLED);
        Order saved = orderRepository.save(order);
        return mapToOrderResponse(saved);
    }
}