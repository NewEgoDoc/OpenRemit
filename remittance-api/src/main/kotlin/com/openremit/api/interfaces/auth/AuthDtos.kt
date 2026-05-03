package com.openremit.api.interfaces.auth

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class SignupRequest(
    @field:Email val email: String,
    @field:NotBlank @field:Size(min = 8, max = 100) val password: String,
    @field:NotBlank @field:Size(max = 100) val name: String,
)

data class SignupResponse(
    val id: Long,
    val email: String,
    val name: String,
)

data class LoginRequest(
    @field:Email val email: String,
    @field:NotBlank val password: String,
)

data class LoginResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresAt: Instant,
)
