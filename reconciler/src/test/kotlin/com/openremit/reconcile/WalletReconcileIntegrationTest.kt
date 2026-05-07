package com.openremit.reconcile

import com.openremit.reconcile.infrastructure.persistence.ReconciliationRepository
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 정산 잡의 end-to-end 검증.
 *
 * 시나리오: 정합 wallet 1개 + 불일치 wallet 1개를 주입한 뒤 잡을 실행해
 * reconciliations 테이블에 mismatch_count=1 이 기록되는지, details JSON 에 해당 walletId 가 들어가는지 확인.
 *
 * 외부 (remittance-api) 소유 테이블은 reconciler 의 production schema 에 없으므로 테스트 환경에서 minimal schema 로 직접 생성한다.
 * docker-compose 환경에서는 remittance-api 의 Flyway 마이그레이션이 보장한다.
 */
@SpringBootTest
@Import(ReconcilerTestcontainersConfig::class)
class WalletReconcileIntegrationTest @Autowired constructor(
    private val jobOperator: JobOperator,
    @Qualifier("walletReconcileJob") private val job: Job,
    private val reconciliationRepository: ReconciliationRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {

    @BeforeTest
    fun setup() {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS wallets (
                id          BIGINT          NOT NULL AUTO_INCREMENT,
                user_id     BIGINT          NOT NULL,
                currency    CHAR(3)         NOT NULL,
                balance     DECIMAL(19, 4)  NOT NULL DEFAULT 0,
                version     BIGINT          NOT NULL DEFAULT 0,
                updated_at  TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                PRIMARY KEY (id)
            ) ENGINE=InnoDB
            """.trimIndent()
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS wallet_transactions (
                id              BIGINT          NOT NULL AUTO_INCREMENT,
                wallet_id       BIGINT          NOT NULL,
                amount          DECIMAL(19, 4)  NOT NULL,
                balance_after   DECIMAL(19, 4)  NOT NULL,
                reference_type  VARCHAR(20)     NOT NULL,
                reference_id    BIGINT          NOT NULL,
                created_at      TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                PRIMARY KEY (id),
                INDEX idx_wallet_transactions_wallet (wallet_id, id)
            ) ENGINE=InnoDB
            """.trimIndent()
        )
        jdbcTemplate.execute("DELETE FROM wallet_transactions")
        jdbcTemplate.execute("DELETE FROM wallets")
        reconciliationRepository.deleteAllInBatch()
    }

    @Test
    fun `consistent wallet plus inconsistent wallet — job records mismatch_count = 1`() {
        // 정합 wallet (id=1): balance=100, ledger 합=100, 마지막 balance_after=100
        jdbcTemplate.update(
            "INSERT INTO wallets (id, user_id, currency, balance) VALUES (?, ?, ?, ?)",
            1L, 100L, "KRW", 100,
        )
        jdbcTemplate.update(
            """INSERT INTO wallet_transactions
               (wallet_id, amount, balance_after, reference_type, reference_id)
               VALUES (?, ?, ?, ?, ?)""",
            1L, 100, 100, "PAYMENT", 1L,
        )

        // 불일치 wallet (id=2): balance=900 인데 ledger 합=800 (1행 누락) → A 위반
        // 누락된 행은 의도적으로 INSERT 하지 않음. 마지막 balance_after=800 → B 도 위반.
        jdbcTemplate.update(
            "INSERT INTO wallets (id, user_id, currency, balance) VALUES (?, ?, ?, ?)",
            2L, 200L, "KRW", 900,
        )
        jdbcTemplate.update(
            """INSERT INTO wallet_transactions
               (wallet_id, amount, balance_after, reference_type, reference_id)
               VALUES (?, ?, ?, ?, ?)""",
            2L, 800, 800, "REMITTANCE", 1L,
        )

        val params = JobParametersBuilder()
            .addLong("runAt", System.currentTimeMillis())
            .toJobParameters()
        val execution = jobOperator.start(job, params)

        assertEquals(BatchStatus.COMPLETED, execution.status)

        val recs = reconciliationRepository.findAll()
        assertEquals(1, recs.size)
        val rec = recs.single()
        assertEquals(2, rec.totalCount)
        assertEquals(1, rec.mismatchCount)

        @Suppress("UNCHECKED_CAST")
        val mismatches = objectMapper.readValue(rec.details, List::class.java) as List<Map<String, Any>>
        assertEquals(1, mismatches.size)
        val m = mismatches.single()
        // application.yaml 의 spring.jackson.property-naming-strategy=SNAKE_CASE 가 적용된다.
        assertEquals(2L, (m["wallet_id"] as Number).toLong())
        assertEquals(true, m["violates_a"])
        assertEquals(true, m["violates_b"])
    }

    @Test
    fun `all wallets consistent — mismatch_count = 0`() {
        jdbcTemplate.update(
            "INSERT INTO wallets (id, user_id, currency, balance) VALUES (?, ?, ?, ?)",
            10L, 1000L, "KRW", 500,
        )
        jdbcTemplate.update(
            """INSERT INTO wallet_transactions
               (wallet_id, amount, balance_after, reference_type, reference_id)
               VALUES (?, ?, ?, ?, ?)""",
            10L, 500, 500, "PAYMENT", 1L,
        )

        val params = JobParametersBuilder()
            .addLong("runAt", System.currentTimeMillis())
            .toJobParameters()
        val execution = jobOperator.start(job, params)

        assertEquals(BatchStatus.COMPLETED, execution.status)
        val rec = reconciliationRepository.findAll().single()
        assertEquals(1, rec.totalCount)
        assertEquals(0, rec.mismatchCount)
        assertTrue(rec.details == "[]")
    }
}
