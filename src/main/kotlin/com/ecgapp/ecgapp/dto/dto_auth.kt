package com.ecgapp.ecgapp.dto


data class RegisterRequest(
    val name: String,
    val email: String,
    val password: String,
    val age: Int,
    val gender: String
)

data class LoginRequest(
    val email: String,
    val password: String
)

data class BasicResponse(
    val message: String
)

data class LoginResponse(
    val success: Boolean,
    val message: String,
    val userId: Long,
)


data class RegisterResponse(
    val success: Boolean,
    val message: String,
    val userId: Long,
)