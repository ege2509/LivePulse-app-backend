package com.ecgapp.ecgapp.controllers

import com.ecgapp.ecgapp.models.User
import com.ecgapp.ecgapp.repository.UserRepository
import com.ecgapp.ecgapp.service.UserService
import com.ecgapp.ecgapp.dto.RegisterRequest
import com.ecgapp.ecgapp.dto.LoginRequest
import com.ecgapp.ecgapp.dto.BasicResponse
import com.ecgapp.ecgapp.dto.LoginResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/users")
class UserController(
    private val userRepository: UserRepository,
    private val userService: UserService

) {

    @GetMapping
    fun getAllUsers(): ResponseEntity<List<User>> {
        val users = userRepository.findAll()
        return ResponseEntity.ok(users)
    }

    @GetMapping("/{id}")
    fun getUserProfile(@PathVariable id: Long): ResponseEntity<User> {
        val user = userRepository.findById(id)
        return if (user.isPresent) {
            ResponseEntity.ok(user.get())
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @PutMapping("/{id}")
    fun updateUserProfile(@PathVariable id: Long, @RequestBody userDetails: User): ResponseEntity<User> {
        val user = userRepository.findById(id)
        return if (user.isPresent) {
            val existingUser = user.get()
            val updatedUser = existingUser.copy(
                email = userDetails.email,
                age = userDetails.age,
                gender = userDetails.gender,
                profilePicture = userDetails.profilePicture
            )
            ResponseEntity.ok(userRepository.save(updatedUser))
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @PostMapping("/register")
    fun register(@RequestBody request: RegisterRequest): ResponseEntity<BasicResponse> {
        return ResponseEntity.ok(userService.register(request))
    }

    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): ResponseEntity<LoginResponse> {
        return ResponseEntity.ok(userService.login(request))
    }
}

