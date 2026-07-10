package com.enterprise.security.adminservice.web;

import com.enterprise.security.adminservice.service.DocumentNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
public class DocumentExceptionHandler {

    @ExceptionHandler(DocumentNotFoundException.class)
    public ProblemDetail handleNotFound(DocumentNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Document Not Found");
        problem.setDetail(ex.getMessage());
        problem.setType(URI.create("https://enterprise-security.example.com/errors/not-found"));
        return problem;
    }
}
