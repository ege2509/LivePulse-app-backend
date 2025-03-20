package com.ecgapp.ecgapp.service

import com.ecgapp.ecgapp.models.User
import com.ecgapp.ecgapp.repository.UserRepository
import java.time.LocalDateTime
import com.ecgapp.ecgapp.dto.RegisterRequest
import com.ecgapp.ecgapp.dto.LoginRequest
import com.ecgapp.ecgapp.dto.BasicResponse
import org.springframework.stereotype.Service

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UsernameNotFoundException


@Service
class UserService(
    private val userRepository: UserRepository
) {

    fun getUserByEmail(email: String): User? {
        return userRepository.findByEmail(email)
    }

    fun createUser(user: User): User {
        return userRepository.save(user)
    }

    fun register(request: RegisterRequest): BasicResponse {
        if (userRepository.findByEmail(request.email) != null) {
            return BasicResponse("Email already taken")
        }
    
        val newUser = User(
            name = request.name,
            email = request.email,
            password = request.password,
            age = request.age,
            gender = request.gender,
            createdAt = LocalDateTime.now(),
            profilePicture = null // not used during registration
        )
    
        userRepository.save(newUser)
        return BasicResponse("User registered successfully")
    }

    fun login(request: LoginRequest): BasicResponse {
        val user = userRepository.findByEmail(request.email)
            ?: return BasicResponse("Invalid username or password")

        return if (user.password == request.password) {
            BasicResponse("Login successful")
        } else {
            BasicResponse("Invalid username or password")
        }
    }

    fun getCurrentUser(): User {
        val email = SecurityContextHolder.getContext().authentication.name
        return userRepository.findByEmail(email)
            ?: throw UsernameNotFoundException("User not found")
    }
}
