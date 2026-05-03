package com.openremit.api.application.auth

import com.openremit.api.domain.User
import com.openremit.api.domain.Wallet
import com.openremit.api.infrastructure.persistence.UserRepository
import com.openremit.api.infrastructure.persistence.WalletRepository
import com.openremit.common.Currency
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SignupUseCase(
    private val userRepository: UserRepository,
    private val walletRepository: WalletRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    @Transactional
    fun signup(command: SignupCommand): SignupResult {
        if (userRepository.existsByEmail(command.email)) {
            throw EmailAlreadyExistsException(command.email)
        }

        val user = userRepository.save(
            User(
                email = command.email,
                passwordHash = passwordEncoder.encode(command.password)!!,
                name = command.name,
            )
        )
        walletRepository.save(Wallet(userId = user.id, currency = Currency.KRW))

        return SignupResult(userId = user.id, email = user.email, name = user.name)
    }

    data class SignupCommand(val email: String, val password: String, val name: String)
    data class SignupResult(val userId: Long, val email: String, val name: String)
}
