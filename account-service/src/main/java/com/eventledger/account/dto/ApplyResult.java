package com.eventledger.account.dto;

/**
 * Internal carrier telling the controller whether a transaction was newly
 * applied (201) or was an idempotent replay (200).
 */
public record ApplyResult(TransactionResponse transaction, boolean created) {
}
