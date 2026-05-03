package com.openremit.api.interfaces.error

import com.openremit.api.application.auth.EmailAlreadyExistsException
import com.openremit.api.application.auth.InvalidCredentialsException
import com.openremit.api.domain.InsufficientBalanceException
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
