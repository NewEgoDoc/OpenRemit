package com.openremit.api.infrastructure.lock

import org.redisson.api.RedissonClient
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit

class WalletLockAcquireFailedException(userId: Long) :
    RuntimeException("Failed to acquire wallet lock for userId=$userId")

@Component
class WalletLockService(
    private val redissonClient: RedissonClient,
) {
    // leaseTime을 지정하지 않는 tryLock 오버로드를 호출 → Redisson watchdog이
    // lockWatchdogTimeout(기본 30초)의 1/3 주기로 TTL을 자동 갱신.
    // 작업이 길어져도 만료되지 않으며, 보유 스레드 사망/JVM 종료 시에는 watchdog
    // 갱신이 멈춰 자동으로 풀린다.
    fun <T> withWalletLock(
        userId: Long,
        waitTime: Duration = DEFAULT_WAIT,
        block: () -> T,
    ): T {
        val lock = redissonClient.getLock("wallet:$userId")
        val acquired = lock.tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS)
        if (!acquired) {
            throw WalletLockAcquireFailedException(userId)
        }
        try {
            return block()
        } finally {
            if (lock.isHeldByCurrentThread) {
                lock.unlock()
            }
        }
    }

    companion object {
        private val DEFAULT_WAIT = Duration.ofSeconds(3)
    }
}
