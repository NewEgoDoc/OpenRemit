package com.openremit.api.application.auth

class EmailAlreadyExistsException(email: String) :
    RuntimeException("Email already exists: $email")

class InvalidCredentialsException : RuntimeException("Invalid email or password")
