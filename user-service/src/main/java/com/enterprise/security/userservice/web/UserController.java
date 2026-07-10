package com.enterprise.security.userservice.web;

import com.enterprise.security.userservice.dto.UpdateUserRequest;
import com.enterprise.security.userservice.dto.UserResponse;
import com.enterprise.security.userservice.service.UserService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public UserResponse getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        return UserResponse.from(userService.getUser(jwt.getSubject()));
    }

    @GetMapping("/{id}")
    public UserResponse getUser(@PathVariable String id) {
        return UserResponse.from(userService.getUser(id));
    }

    @GetMapping
    public List<UserResponse> listUsers() {
        return userService.listUsersInCurrentTenant().stream().map(UserResponse::from).toList();
    }

    @PutMapping("/{id}")
    public UserResponse updateUser(@PathVariable String id, @Valid @RequestBody UpdateUserRequest request) {
        return UserResponse.from(userService.updateUser(id, request));
    }

    @DeleteMapping("/{id}")
    public void deactivateUser(@PathVariable String id) {
        userService.deactivateUser(id);
    }
}
