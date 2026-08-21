package com.example.integration.contract;

/**
 * Event type shared by the host and the integration plugin through exact class identity.
 */
public record ContractEvent(String message) {
}
