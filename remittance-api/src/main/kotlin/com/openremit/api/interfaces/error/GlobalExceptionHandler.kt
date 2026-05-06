package com.openremit.api.interfaces.error

import com.openremit.api.application.auth.EmailAlreadyExistsException
import com.openremit.api.application.auth.InvalidCredentialsException
import com.openremit.api.domain.IllegalStateTransitionException
import com.openremit.api.domain.InsufficientBalanceException
import com.openremit.api.infrastructure.fx.FxRateBadRequestException
import com.openremit.api.infrastructure.fx.FxRateUnavailableException
import com.openremit.api.infrastructure.idempotency.IdempotencyConflictException
import com.openremit.api.infrastructure.idempotency.IdempotencyInProgressException
import com.openremit.api.infrastructure.idempotency.IdempotencyKeyTooLongException
import com.openremit.api.infrastructure.idempotency.MissingIdempotencyKeyException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(InvalidCredentialsException::class)
    fun handleInvalidCredentials(ex: InvalidCredentialsException): ProblemDetail =
        problem(HttpStatus.UNAUTHORIZED, "invalid-credentials", "Invalid Credentials", ex.message)

    @ExceptionHandler(EmailAlreadyExistsException::class)
    fun handleEmailExists(ex: EmailAlreadyExistsException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "email-already-exists", "Email Already Exists", ex.message)

    @ExceptionHandler(InsufficientBalanceException::class)
    fun handleInsufficientBalance(ex: InsufficientBalanceException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "insufficient-balance", "Insufficient Balance", ex.message)

    @ExceptionHandler(IllegalStateTransitionException::class)
    fun handleIllegalTransition(ex: IllegalStateTransitionException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "illegal-state-transition", "Illegal State Transition", ex.message)

    @ExceptionHandler(IdempotencyConflictException::class)
    fun handleIdempotencyConflict(ex: IdempotencyConflictException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "idempotency-conflict", "Idempotency Conflict", ex.message)

    @ExceptionHandler(MissingIdempotencyKeyException::class)
    fun handleMissingIdempotencyKey(ex: MissingIdempotencyKeyException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "missing-idempotency-key", "Missing Idempotency-Key", ex.message)

    @ExceptionHandler(IdempotencyKeyTooLongException::class)
    fun handleIdempotencyKeyTooLong(ex: IdempotencyKeyTooLongException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "idempotency-key-too-long", "Idempotency-Key Too Long", ex.message)

    @ExceptionHandler(IdempotencyInProgressException::class)
    fun handleIdempotencyInProgress(ex: IdempotencyInProgressException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "idempotency-in-progress", "Idempotency Request In Progress", ex.message)

    @ExceptionHandler(FxRateUnavailableException::class)
    fun handleFxRateUnavailable(ex: FxRateUnavailableException): ProblemDetail =
        problem(HttpStatus.SERVICE_UNAVAILABLE, "fx-rate-unavailable", "FX Rate Unavailable", ex.message)

    @ExceptionHandler(FxRateBadRequestException::class)
    fun handleFxRateBadRequest(ex: FxRateBadRequestException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "fx-rate-bad-request", "FX Rate Bad Request", ex.message)

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "invalid-argument", "Invalid Argument", ex.message)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ProblemDetail {
        val detail = ex.bindingResult.fieldErrors.joinToString { "${it.field}: ${it.defaultMessage}" }
        return problem(HttpStatus.BAD_REQUEST, "validation-failed", "Validation Failed", detail)
    }

    private fun problem(
        status: HttpStatus,
        slug: String,
        title: String,
        detail: String?,
    ): ProblemDetail = ProblemDetail.forStatusAndDetail(status, detail ?: title).apply {
        type = URI.create("https://openremit.dev/errors/$slug")
        this.title = title
    }
}
