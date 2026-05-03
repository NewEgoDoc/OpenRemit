package com.openremit.common

data class ReceiverInfo(val name: String, val account: String) {
    init {
        require(name.isNotBlank()) { "Receiver name must not be blank" }
        require(account.isNotBlank()) { "Receiver account must not be blank" }
        require(name.length <= 100) { "Receiver name too long" }
        require(account.length <= 100) { "Receiver account too long" }
    }
}
