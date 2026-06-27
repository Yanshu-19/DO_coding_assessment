package com.example.jobqueue.exception;

public class TransientJobException extends RuntimeException {

    public TransientJobException(String message) {
        super(message);
    }
}
