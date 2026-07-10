package com.enterprise.security.orderservice.service;

import com.enterprise.security.common.audit.Audited;
import com.enterprise.security.common.tenant.TenantContext;
import com.enterprise.security.orderservice.client.RemoteUser;
import com.enterprise.security.orderservice.client.UserServiceClient;
import com.enterprise.security.orderservice.domain.Order;
import com.enterprise.security.orderservice.domain.OrderStatus;
import com.enterprise.security.orderservice.dto.CreateOrderRequest;
import com.enterprise.security.orderservice.repository.OrderRepository;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserServiceClient userServiceClient;

    public OrderService(OrderRepository orderRepository, UserServiceClient userServiceClient) {
        this.orderRepository = orderRepository;
        this.userServiceClient = userServiceClient;
    }

    /**
     * Cross-service call validates the owner is a real, active user in the same tenant before the
     * order is persisted — the client-credentials token used for that call is what authorizes
     * order-service to read user-service's /internal API at all.
     */
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    @Audited(action = "ORDER_CREATE")
    public Order createOrder(CreateOrderRequest request, String ownerId) {
        RemoteUser owner = userServiceClient.getUser(ownerId);
        if (!owner.active()) {
            throw new IllegalStateException("User is not active: " + ownerId);
        }
        Order order = new Order(UUID.randomUUID().toString(), ownerId, owner.tenantId(),
                request.item(), request.quantity(), OrderStatus.CREATED);
        return orderRepository.save(order);
    }

    @Transactional(readOnly = true)
    @PostAuthorize("hasRole('ADMIN') or hasRole('SUPPORT') or returnObject.ownerId == authentication.name")
    public Order getOrder(String id) {
        return orderRepository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Order> listMyOrders(String ownerId) {
        return orderRepository.findByOwnerId(ownerId);
    }

    @Transactional(readOnly = true)
    @Secured({"ROLE_ADMIN", "ROLE_SUPPORT"})
    public List<Order> listOrdersInCurrentTenant() {
        return orderRepository.findByTenantId(TenantContext.get());
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPPORT')")
    @Audited(action = "ORDER_CANCEL")
    public Order cancelOrder(String id) {
        Order order = orderRepository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
        order.setStatus(OrderStatus.CANCELLED);
        return orderRepository.save(order);
    }
}
