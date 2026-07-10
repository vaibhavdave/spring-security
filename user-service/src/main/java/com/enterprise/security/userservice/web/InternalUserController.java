package com.enterprise.security.userservice.web;

import com.enterprise.security.userservice.dto.UserResponse;
import com.enterprise.security.userservice.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service surface. Callers authenticate via OAuth2 client-credentials (Keycloak issues
 * a token carrying ROLE_SERVICE for the calling service's client) rather than a user's bearer
 * token — see order-service's OrderService for the calling side.
 */
@RestController
@RequestMapping("/internal/users")
public class InternalUserController {

    private final UserService userService;

    public InternalUserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/{id}")
    public UserResponse getUser(@PathVariable String id) {
        return UserResponse.from(userService.getUserForServiceCall(id));
    }
}
