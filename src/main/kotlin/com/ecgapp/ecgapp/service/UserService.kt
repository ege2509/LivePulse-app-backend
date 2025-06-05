package com.ecgapp.ecgapp.service

import com.ecgapp.ecgapp.models.User
import com.ecgapp.ecgapp.repository.UserRepository
import java.time.LocalDateTime
import com.ecgapp.ecgapp.dto.RegisterRequest
import com.ecgapp.ecgapp.dto.RegisterResponse
import com.ecgapp.ecgapp.dto.LoginRequest
import com.ecgapp.ecgapp.dto.BasicResponse
import com.ecgapp.ecgapp.dto.LoginResponse
import org.springframework.stereotype.Service

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UsernameNotFoundException


@Service
class UserService(
    private val userRepository: UserRepository,
    private val medicalInfoService: MedicalInfoService
) {

    fun getUserByEmail(email: String): User? {
        return userRepository.findByEmail(email)
    }

    fun createUser(user: User): User {
        return userRepository.save(user)
    }

fun register(request: RegisterRequest): RegisterResponse {
    val existingUser = userRepository.findByEmail(request.email)
    if (existingUser != null) {
        return RegisterResponse(
            success = false,
            message = "Email already taken",
            userId = -1L
        )
    }

    val newUser = User(
        name = request.name,
        email = request.email,
        password = request.password,
        age = request.age,
        gender = request.gender,
        createdAt = LocalDateTime.now(),
        profilePicture = null
    )

    val savedUser = userRepository.save(newUser)


    // Automatically create empty medical info for the new user
    medicalInfoService.createMedicalInfo(
                userId = savedUser.id,
                bloodType = null,
                allergies = null,
                medications = null
            )

    return RegisterResponse(
        success = true,
        message = "User registered successfully",
        userId = savedUser.id ?: -1L
    )
}
fun login(request: LoginRequest): LoginResponse {
    val user = userRepository.findByEmail(request.email)
    return if (user == null) {
        LoginResponse(
            success = false,
            message = "Account doesn't exist",
            userId = -1  // or any invalid id, since login failed
        )
    } else if (user.password == request.password) {
        LoginResponse(
            success = true,
            message = "Login successful",
            userId = user.id
        )
    } else {
        LoginResponse(
            success = false,
            message = "Invalid username or password",
            userId = -1
        )
    }
}

    fun getCurrentUser(): User {
        val email = SecurityContextHolder.getContext().authentication.name
        return userRepository.findByEmail(email)
            ?: throw UsernameNotFoundException("User not found")
    }
}
