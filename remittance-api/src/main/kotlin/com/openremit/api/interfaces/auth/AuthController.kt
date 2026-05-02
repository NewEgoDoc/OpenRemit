package com.openremit.api.interfaces.auth

import com.openremit.api.application.auth.LoginUseCase
import com.openremit.api.application.auth.SignupUseCase
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val signupUseCase: SignupUseCase,
    private val loginUseCase: LoginUseCase,
) {
    @PostMapping("/signup")
    fun signup(@Valid @RequestBody request: SignupRequest): ResponseEntity<SignupResponse> {
        val result = signupUseCase.signup(
            SignupUseCase.SignupCommand(
                email = request.email,
                password = request.password,
                name = request.name,
            )
        )
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(SignupResponse(id = result.userId, email = result.email, name = result.name))
    }

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): LoginResponse {
        val result = loginUseCase.login(
            LoginUseCase.LoginCommand(email = request.email, password = request.password)
        )
        return LoginResponse(
            accessToken = result.accessToken,
            expiresAt = result.expiresAt,
        )
    }
}
