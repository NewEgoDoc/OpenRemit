package com.openremit.api.remittance

import com.openremit.api.TestcontainersConfig
import com.openremit.api.application.remittance.RemittanceCreateUseCase
import com.openremit.api.domain.User
import com.openremit.api.domain.Wallet
import com.openremit.api.infrastructure.fx.FxRateCache
import com.openremit.api.infrastructure.persistence.RemittanceRepository
import com.openremit.api.infrastructure.persistence.UserRepository
import com.openremit.api.infrastructure.persistence.WalletRepository
import com.openremit.common.Currency
import com.openremit.common.Money
import com.openremit.common.ReceiverInfo
import com.openremit.payment.PaymentMethod
import com.openremit.payment.PaymentRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@SpringBootTest
@Import(TestcontainersConfig::class)
class RemittanceConcurrencyTest @Autowired constructor(
    private val useCase: RemittanceCreateUseCase,
    private val userRepository: UserRepository,
    private val walletRepository: WalletRepository,
    private val remittanceRepository: RemittanceRepository,
    private val paymentRepository: PaymentRepository,
    private val fxRateCache: FxRateCache,
) {

    private var userId: Long = 0L

    @BeforeTest
    fun setup() {
        val user = userRepository.saveAndFlush(
            User(email = "concurrent@example.com", passwordHash = "hash", name = "Concurrent")
        )
        userId = user.id
        val wallet = Wallet(userId = userId, currency = Currency.KRW)
        wallet.deposit(Money.of("100000", Currency.KRW))
        walletRepository.saveAndFlush(wallet)
        fxRateCache.put(Currency.KRW, Currency.USD, BigDecimal("0.000735"))
    }

    @AfterTest
    fun cleanup() {
        remittanceRepository.deleteAllInBatch()
        paymentRepository.deleteAllInBatch()
        walletRepository.deleteAllInBatch()
        userRepository.deleteAllInBatch()
    }

    @Test
    fun `10 concurrent withdraw requests preserve balance integrity`() {
        val threadCount = 10
        val perRequest = BigDecimal("30000")
        val initialBalance = BigDecimal("100000")

        val executor = Executors.newFixedThreadPool(threadCount)
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val success = AtomicInteger(0)
        val failure = AtomicInteger(0)

        repeat(threadCount) {
            executor.submit {
                ready.countDown()
                start.await()
                try {
                    useCase.create(
                        RemittanceCreateUseCase.CreateCommand(
                            userId = userId,
                            fromCurrency = Currency.KRW,
                            fromAmount = perRequest,
                            toCurrency = Currency.USD,
                            receiver = ReceiverInfo(name = "John", account = "1234"),
                            paymentMethod = PaymentMethod.BANK_TRANSFER,
                        )
                    )
                    success.incrementAndGet()
                } catch (_: Throwable) {
                    failure.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }
        }

        ready.await()
        start.countDown()
        check(done.await(30, TimeUnit.SECONDS)) { "Threads did not finish in time" }
        executor.shutdown()

        val expectedSuccess = (initialBalance.toLong() / perRequest.toLong()).toInt()
        val expectedFailure = threadCount - expectedSuccess
        val expectedRemaining = initialBalance - perRequest * BigDecimal(expectedSuccess)

        assertEquals(expectedSuccess, success.get(), "successful withdraws")
        assertEquals(expectedFailure, failure.get(), "failed withdraws")

        val wallet = walletRepository.findByUserId(userId)!!
        assertEquals(
            0,
            wallet.balance.amount.compareTo(expectedRemaining.setScale(Money.SCALE)),
            "wallet balance must equal initial minus successful withdraws",
        )
        assertEquals(expectedSuccess.toLong(), remittanceRepository.count())
        assertEquals(expectedSuccess.toLong(), paymentRepository.count())
    }
}
