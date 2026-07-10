package com.enterprise.security.orderservice.client;

import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServletOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Calls user-service's /internal endpoint using the OAuth2 client-credentials grant: this
 * process, not any human user, is the one authenticating to Keycloak here, and gets back a token
 * scoped to the "order-service" client (mapped to ROLE_SERVICE in the realm). Istio's mTLS +
 * AuthorizationPolicy provide a second, transport-level layer of the same service identity.
 * WebClient is used purely as an HTTP client here (this remains a servlet application); Spring
 * Security's client-credentials support for the newer blocking RestClient needs Spring Security
 * 6.3.4+, one patch ahead of what Boot 3.3.4 resolves.
 */
@Component
public class UserServiceClient {

    private static final String REGISTRATION_ID = "user-service-client";

    private final WebClient webClient;

    public UserServiceClient(WebClient.Builder webClientBuilder,
                              OAuth2AuthorizedClientManager authorizedClientManager,
                              org.springframework.core.env.Environment env) {
        ServletOAuth2AuthorizedClientExchangeFilterFunction oauth2Filter =
                new ServletOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
        oauth2Filter.setDefaultClientRegistrationId(REGISTRATION_ID);

        this.webClient = webClientBuilder
                .baseUrl(env.getProperty("services.user-service.base-url", "http://localhost:8081"))
                .apply(oauth2Filter.oauth2Configuration())
                .build();
    }

    public RemoteUser getUser(String userId) {
        return webClient.get()
                .uri("/internal/users/{id}", userId)
                .retrieve()
                .bodyToMono(RemoteUser.class)
                .block();
    }
}
