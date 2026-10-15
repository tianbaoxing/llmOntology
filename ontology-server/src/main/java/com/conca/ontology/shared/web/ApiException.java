package com.conca.ontology.shared.web;

/**
 * @author tianbaoxing
 * @date 2026-08-19
 */
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
