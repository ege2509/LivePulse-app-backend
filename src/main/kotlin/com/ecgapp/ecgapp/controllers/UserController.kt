package com.ecgapp.ecgapp.controller

import com.ecgapp.ecgapp.models.User
import com.ecgapp.ecgapp.service.UserService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/users")
class UserController(private val userService: UserService) {

    @GetMapping("/{email}")
    fun getUserByEmail(@PathVariable email: String): User? {
        return userService.getUserByEmail(email)
    }

    @PostMapping
    fun createUser(@RequestBody user: User): User {
        return userService.createUser(user)
    }
}
