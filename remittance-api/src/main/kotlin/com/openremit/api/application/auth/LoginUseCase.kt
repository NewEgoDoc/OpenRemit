package com.openremit.api.application.auth

import com.openremit.api.infrastructure.persistence.UserRepository
import com.openremit.api.infrastructure.security.JwtTokenProvider
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class LoginUseCase(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtTokenProvider: JwtTokenProvider,
) {
    @Transactional(readOnly = true)
    fun login(command: LoginCommand): LoginResult {
        val user = userRepository.findByEmail(command.email)
            ?: throw InvalidCredentialsException()
        if (!passwordEncoder.matches(command.password, user.passwordHash)) {
            throw InvalidCredentialsException()
        }
        val issued = jwtTokenProvider.issue(userId = user.id, email = user.email)
        return LoginResult(
            accessToken = issued.token,
            expiresAt = issued.expiresAt,
        )
    }

    data class LoginCommand(val email: String, val password: String)
    data class LoginResult(val accessToken: String, val expiresAt: Instant)
}
