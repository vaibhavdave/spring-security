package com.enterprise.security.userservice.repository;

import com.enterprise.security.userservice.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserRepository extends JpaRepository<User, String> {

    List<User> findByTenantId(String tenantId);
}
