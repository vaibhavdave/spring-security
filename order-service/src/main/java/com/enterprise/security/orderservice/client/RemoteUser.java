package com.enterprise.security.orderservice.client;

/** Local projection of the fields order-service needs from user-service — deliberately not
 * sharing user-service's DTO, since these are two independently deployable bounded contexts. */
public record RemoteUser(String id, String tenantId, boolean active) {
}
