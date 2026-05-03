package com.openremit.api.auth

import com.openremit.api.TestcontainersConfig
import com.openremit.api.infrastructure.persistence.UserRepository
import com.openremit.api.infrastructure.persistence.WalletRepository
import com.openremit.common.Currency
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import tools.jackson.databind.ObjectMapper
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class AuthIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val userRepository: UserRepository,
    private val walletRepository: WalletRepository,
    private val jwtDecoder: JwtDecoder,
) {

    @AfterTest
    fun cleanup() {
        walletRepository.deleteAllInBatch()
        userRepository.deleteAllInBatch()
    }

    @Test
    fun `signup creates user and KRW wallet, returns 201`() {
        val response = postJson(
            "/api/v1/auth/signup",
            mapOf("email" to "alice@example.com", "password" to "password123", "name" to "Alice"),
        )

        assertEquals(201, response.status)
        val body = readMap(response.contentAsString)
        assertEquals("alice@example.com", body["email"])
        assertEquals("Alice", body["name"])

        val user = userRepository.findByEmail("alice@example.com")
        assertNotNull(user)
        val wallet = walletRepository.findByUserId(user.id)
        assertNotNull(wallet)
        assertEquals(Currency.KRW, wallet.currency)
    }

    @Test
    fun `signup with duplicate email returns 409`() {
        signup("dup@example.com", "password123", "Bob")

        val response = postJson(
            "/api/v1/auth/signup",
            mapOf("email" to "dup@example.com", "password" to "password123", "name" to "Bob2"),
        )

        assertEquals(409, response.status)
    }

    @Test
    fun `login with correct password returns 200 and valid JWT`() {
        signup("carol@example.com", "password123", "Carol")

        val response = postJson(
            "/api/v1/auth/login",
            mapOf("email" to "carol@example.com", "password" to "password123"),
        )

        assertEquals(200, response.status)
        val body = readMap(response.contentAsString)
        val token = body["accessToken"] as String
        assertTrue(token.isNotBlank())

        val jwt = jwtDecoder.decode(token)
        assertEquals("https://openremit.dev", jwt.getClaimAsString("iss"))
        assertEquals("carol@example.com", jwt.getClaimAsString("email"))
    }

    @Test
    fun `login with wrong password returns 401`() {
        signup("dave@example.com", "password123", "Dave")

        val response = postJson(
            "/api/v1/auth/login",
            mapOf("email" to "dave@example.com", "password" to "wrong-password"),
        )

        assertEquals(401, response.status)
    }

    @Test
    fun `login with unknown email returns 401`() {
        val response = postJson(
            "/api/v1/auth/login",
            mapOf("email" to "ghost@example.com", "password" to "password123"),
        )

        assertEquals(401, response.status)
    }

    private fun signup(email: String, password: String, name: String) {
        val response = postJson(
            "/api/v1/auth/signup",
            mapOf("email" to email, "password" to password, "name" to name),
        )
        check(response.status == 201) {
            "signup failed: ${response.status} ${response.contentAsString}"
        }
    }

    private fun postJson(path: String, body: Map<String, Any>) = mockMvc.perform(
        MockMvcRequestBuilders.post(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body))
    ).andReturn().response

    @Suppress("UNCHECKED_CAST")
    private fun readMap(json: String): Map<String, Any> =
        objectMapper.readValue(json, Map::class.java) as Map<String, Any>
}
