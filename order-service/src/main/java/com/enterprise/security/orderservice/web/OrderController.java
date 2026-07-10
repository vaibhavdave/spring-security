package com.enterprise.security.orderservice.web;

import com.enterprise.security.orderservice.dto.CreateOrderRequest;
import com.enterprise.security.orderservice.dto.OrderResponse;
import com.enterprise.security.orderservice.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse createOrder(@Valid @RequestBody CreateOrderRequest request, @AuthenticationPrincipal Jwt jwt) {
        return OrderResponse.from(orderService.createOrder(request, jwt.getSubject()));
    }

    @GetMapping("/{id}")
    public OrderResponse getOrder(@PathVariable String id) {
        return OrderResponse.from(orderService.getOrder(id));
    }

    @GetMapping("/mine")
    public List<OrderResponse> listMyOrders(@AuthenticationPrincipal Jwt jwt) {
        return orderService.listMyOrders(jwt.getSubject()).stream().map(OrderResponse::from).toList();
    }

    @GetMapping("/tenant")
    public List<OrderResponse> listTenantOrders() {
        return orderService.listOrdersInCurrentTenant().stream().map(OrderResponse::from).toList();
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancelOrder(@PathVariable String id) {
        return OrderResponse.from(orderService.cancelOrder(id));
    }
}
