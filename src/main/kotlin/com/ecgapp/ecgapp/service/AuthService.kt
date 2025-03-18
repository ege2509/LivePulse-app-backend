package com.ecgapp.ecgapp.services

import com.ecgapp.ecgapp.models.User
import com.ecgapp.ecgapp.repository.UserRepository
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: BCryptPasswordEncoder
) {

    fun register(user: User): User {
        // Check if the email already exists
        userRepository.findByEmail(user.email)?.let {
            throw IllegalArgumentException("Email already exists")
        }

        // Hash the password and save the user
        val hashedPassword = passwordEncoder.encode(user.password)
        val newUser = user.copy(password = hashedPassword)
        return userRepository.save(newUser)
    }

    fun login(email: String, password: String): User? {
        // Find the user by email
        val user = userRepository.findByEmail(email)

        // Check if the user exists and the password matches
        return if (user != null && passwordEncoder.matches(password, user.password)) {
            user
        } else {
            null
        }
    }
}
