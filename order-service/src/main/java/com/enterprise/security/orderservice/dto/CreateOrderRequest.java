package com.enterprise.security.orderservice.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateOrderRequest(
        @NotBlank String item,
        @Min(1) int quantity
) {
}
