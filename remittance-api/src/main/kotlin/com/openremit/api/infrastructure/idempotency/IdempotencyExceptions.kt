package com.openremit.api.infrastructure.idempotency

class IdempotencyConflictException(message: String) : RuntimeException(message)

class MissingIdempotencyKeyException :
    RuntimeException("Idempotency-Key header is required for this endpoint")

class IdempotencyKeyTooLongException(actual: Int, max: Int) :
    RuntimeException("Idempotency-Key length $actual exceeds maximum $max")

class IdempotencyInProgressException(key: String) :
    RuntimeException("Idempotency-Key '$key' is currently being processed by another request")
