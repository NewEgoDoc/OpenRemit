package com.openremit.api.remittance

import com.openremit.api.TestcontainersConfig
import com.openremit.api.domain.RemittanceEvent
import com.openremit.api.domain.RemittanceStatus
import com.openremit.api.domain.User
import com.openremit.api.domain.Wallet
import com.openremit.api.infrastructure.fx.FxRateCache
import com.openremit.api.infrastructure.idempotency.IdempotencyKeyRepository
import com.openremit.api.infrastructure.persistence.RemittanceEventRepository
import com.openremit.api.infrastructure.persistence.RemittanceRepository
import com.openremit.api.infrastructure.persistence.UserRepository
import com.openremit.api.infrastructure.persistence.WalletRepository
import com.openremit.common.events.RemittanceEventTopics
import com.openremit.api.infrastructure.security.JwtTokenProvider
import com.openremit.common.Currency
import com.openremit.common.Money
import com.openremit.payment.PaymentRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class RemittanceCreateIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val userRepository: UserRepository,
    private val walletRepository: WalletRepository,
    private val remittanceRepository: RemittanceRepository,
    private val remittanceEventRepository: RemittanceEventRepository,
    private val paymentRepository: PaymentRepository,
    private val idempotencyKeyRepository: IdempotencyKeyRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    private val fxRateCache: FxRateCache,
) {

    companion object {
        // 테스트는 외부 FX 환경(예: docker-compose의 mock-fx-api)에 의존하면 안 된다.
        // 도달 불가 주소로 고정해 cache miss → upstream 실패 → 503 시나리오를 결정적으로 만든다.
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("openremit.fx.base-url") { "http://127.0.0.1:1" }
            // kafka.bootstrap-servers는 TestcontainersConfig가 System property로 주입.
            com.openremit.api.TestcontainersConfig.kafka
        }
    }

    private lateinit var token: String
    private lateinit var userId: String

    @BeforeTest
    fun setup() {
        val user = userRepository.saveAndFlush(
            User(email = "test@example.com", passwordHash = "hash", name = "Test")
        )
        val wallet = Wallet(userId = user.id, currency = Currency.KRW)
        wallet.deposit(Money.of("1000000", Currency.KRW))
        walletRepository.saveAndFlush(wallet)
        userId = user.id.toString()
        token = jwtTokenProvider.issue(userId = user.id, email = user.email).token
        fxRateCache.put(Currency.KRW, Currency.USD, BigDecimal("0.000735"))
    }

    @AfterTest
    fun cleanup() {
        idempotencyKeyRepository.deleteAllInBatch()
        remittanceEventRepository.deleteAllInBatch()
        remittanceRepository.deleteAllInBatch()
        paymentRepository.deleteAllInBatch()
        walletRepository.deleteAllInBatch()
        userRepository.deleteAllInBatch()
    }

    @Test
    fun `POST remittance with valid request returns 201 PAID with computed toAmount`() {
        val key = UUID.randomUUID().toString()
        val response = postRemittance(key, body = standardBody())

        assertEquals(201, response.status)
        val body = readMap(response.contentAsString)
        assertEquals("PAID", body["status"])
        assertEquals(73.5000, (body["to_amount"] as Number).toDouble(), 0.0001)
        assertNotNull(body["payment_id"])
    }

    @Test
    fun `wallet balance is debited and payment is recorded`() {
        val key = UUID.randomUUID().toString()
        postRemittance(key, body = standardBody())

        val wallet = walletRepository.findByUserId(userId.toLong())!!
        assertEquals(0, wallet.balance.compareTo(Money.of("900000", Currency.KRW)))
        assertEquals(1, paymentRepository.count())
    }

    @Test
    fun `paid remittance writes one outbox event in same transaction`() {
        val key = UUID.randomUUID().toString()
        val response = postRemittance(key, body = standardBody())
        assertEquals(201, response.status)

        val remittanceId = (readMap(response.contentAsString)["id"] as Number).toLong()
        val events = remittanceEventRepository
            .findByAggregateTypeAndAggregateIdOrderByIdAsc(
                RemittanceEvent.AGGREGATE_TYPE_REMITTANCE,
                remittanceId.toString(),
            )

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(RemittanceEventTopics.PAID, event.eventType)
        val payload = readMap(event.payload)
        assertEquals(remittanceId, (payload["remittance_id"] as Number).toLong())
        assertEquals(userId.toLong(), (payload["user_id"] as Number).toLong())
        assertEquals("KRW", payload["from_currency"])
        assertEquals("USD", payload["to_currency"])
        assertNotNull(payload["payment_id"])
    }

    @Test
    fun `same idempotency key with same payload returns cached response`() {
        val key = UUID.randomUUID().toString()
        val first = postRemittance(key, body = standardBody())
        val firstId = readMap(first.contentAsString)["id"]

        val second = postRemittance(key, body = standardBody())
        val secondId = readMap(second.contentAsString)["id"]

        assertEquals(201, second.status)
        assertEquals(firstId, secondId)
        assertEquals(1L, remittanceRepository.count())
    }

    @Test
    fun `same idempotency key with different payload returns 409`() {
        val key = UUID.randomUUID().toString()
        postRemittance(key, body = standardBody())

        val different = standardBody().toMutableMap().apply { put("from_amount", 200000) }
        val response = postRemittance(key, body = different)

        assertEquals(409, response.status)
    }

    @Test
    fun `missing idempotency key header returns 400`() {
        val response = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/remittances")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(standardBody()))
        ).andReturn().response

        assertEquals(400, response.status)
    }

    @Test
    fun `same key from different user returns 409 (no response leak)`() {
        val key = UUID.randomUUID().toString()
        postRemittance(key, body = standardBody())

        val otherUser = userRepository.saveAndFlush(
            User(email = "other@example.com", passwordHash = "hash", name = "Other")
        )
        val otherWallet = Wallet(userId = otherUser.id, currency = Currency.KRW)
        otherWallet.deposit(Money.of("1000000", Currency.KRW))
        walletRepository.saveAndFlush(otherWallet)
        val otherToken = jwtTokenProvider.issue(userId = otherUser.id, email = otherUser.email).token

        val response = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/remittances")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $otherToken")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(standardBody()))
        ).andReturn().response

        assertEquals(409, response.status)
    }

    @Test
    fun `idempotency key longer than 100 chars returns 400 before processing`() {
        val tooLong = "x".repeat(101)
        val response = postRemittance(tooLong, body = standardBody())

        assertEquals(400, response.status)
        // No remittance/payment side effect should have occurred
        assertEquals(0L, remittanceRepository.count())
        assertEquals(0L, paymentRepository.count())
    }

    @Test
    fun `insufficient balance returns 400`() {
        val response = postRemittance(
            UUID.randomUUID().toString(),
            body = standardBody().toMutableMap().apply { put("from_amount", 9999999) },
        )
        assertEquals(400, response.status)
        val body = response.contentAsString
        assertTrue(body.contains("Insufficient", ignoreCase = true))
    }

    @Test
    fun `unavailable fx rate returns 503 (no cached rate, upstream unreachable)`() {
        // setup() seeds KRW->USD only; KRW->JPY has no fresh/stale cache and the default
        // openremit.fx.base-url=http://localhost:9999 has no upstream in this test, so
        // the provider exhausts retries and throws FxRateUnavailableException → 503.
        val body = standardBody().toMutableMap().apply { put("to_currency", "JPY") }

        val response = postRemittance(UUID.randomUUID().toString(), body = body)

        assertEquals(503, response.status)
        assertTrue(response.contentAsString.contains("fx-rate-unavailable", ignoreCase = true))
    }

    private fun standardBody(): Map<String, Any> = mapOf(
        "from_currency" to "KRW",
        "from_amount" to 100000,
        "to_currency" to "USD",
        "receiver_name" to "John Doe",
        "receiver_account" to "1234-5678",
        "method" to "BANK_TRANSFER",
    )

    private fun postRemittance(key: String, body: Map<String, Any>) = mockMvc.perform(
        MockMvcRequestBuilders.post("/api/v1/remittances")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body))
    ).andReturn().response

    @Suppress("UNCHECKED_CAST")
    private fun readMap(json: String): Map<String, Any> =
        objectMapper.readValue(json, Map::class.java) as Map<String, Any>
}
