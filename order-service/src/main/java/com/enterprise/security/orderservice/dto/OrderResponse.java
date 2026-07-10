package com.enterprise.security.orderservice.dto;

import com.enterprise.security.orderservice.domain.Order;
import com.enterprise.security.orderservice.domain.OrderStatus;

import java.time.Instant;

public record OrderResponse(
        String id,
        String ownerId,
        String tenantId,
        String item,
        int quantity,
        OrderStatus status,
        Instant createdAt
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getOwnerId(),
                order.getTenantId(),
                order.getItem(),
                order.getQuantity(),
                order.getStatus(),
                order.getCreatedAt());
    }
}
