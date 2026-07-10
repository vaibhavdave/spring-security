package com.enterprise.security.orderservice.repository;

import com.enterprise.security.orderservice.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, String> {

    List<Order> findByOwnerId(String ownerId);

    List<Order> findByTenantId(String tenantId);
}
