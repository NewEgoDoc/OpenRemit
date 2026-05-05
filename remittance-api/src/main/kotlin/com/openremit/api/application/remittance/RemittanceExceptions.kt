package com.openremit.api.application.remittance

class WalletNotFoundException(userId: Long) :
    RuntimeException("Wallet not found for userId=$userId")
