package com.openremit.api.infrastructure.fx

import com.openremit.common.Currency
import org.springframework.stereotype.Component
import java.math.BigDecimal

class FxRateNotSupportedException(message: String) : RuntimeException(message)

@Component
class FxRateProvider {
    fun rate(from: Currency, to: Currency): BigDecimal = when {
        from == to -> BigDecimal.ONE
        from == Currency.KRW && to == Currency.USD -> BigDecimal("0.00073500")
        from == Currency.KRW && to == Currency.JPY -> BigDecimal("0.10500000")
        from == Currency.KRW && to == Currency.PHP -> BigDecimal("0.04200000")
        else -> throw FxRateNotSupportedException("FX rate not supported: $from -> $to")
    }
}
