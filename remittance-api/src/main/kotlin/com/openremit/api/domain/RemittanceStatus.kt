package com.openremit.api.domain

enum class RemittanceStatus {
    REQUESTED,
    PAID,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

class IllegalStateTransitionException(
    val from: RemittanceStatus,
    val to: RemittanceStatus,
) : RuntimeException("Illegal remittance state transition: $from → $to")
