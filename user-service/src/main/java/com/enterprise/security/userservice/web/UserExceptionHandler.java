package com.enterprise.security.userservice.web;

import com.enterprise.security.userservice.service.UserNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Service-specific exception mapping. Registered separately from common-security's
 * GlobalExceptionHandler because Spring picks the most specific @ExceptionHandler match across
 * all advice beans, so this only needs to cover what's unique to user-service.
 */
@RestControllerAdvice
public class UserExceptionHandler {

    @ExceptionHandler(UserNotFoundException.class)
    public ProblemDetail handleNotFound(UserNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("User Not Found");
        problem.setDetail(ex.getMessage());
        problem.setType(URI.create("https://enterprise-security.example.com/errors/not-found"));
        return problem;
    }
}
