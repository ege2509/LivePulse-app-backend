package com.ecgapp.ecgapp.service

import com.ecgapp.ecgapp.models.User
import com.ecgapp.ecgapp.repository.UserRepository
import org.springframework.stereotype.Service

@Service
class UserService(private val userRepository: UserRepository) {

    fun getUserByEmail(email: String): User? {
        return userRepository.findByEmail(email)
    }

    fun createUser(user: User): User {
        return userRepository.save(user)
    }
}
