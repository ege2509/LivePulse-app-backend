package com.ecgapp.ecgapp.models

import java.time.LocalDate
import java.time.LocalDateTime
import com.fasterxml.jackson.annotation.JsonManagedReference

import jakarta.persistence.*

@Entity
@Table(name = "users")
data class User(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "full_name", nullable = false)
    val name: String,

    @Column(nullable = false, unique = true)
    val email: String,

    @Column(name = "password", nullable = false)
    val password: String,

    @Column(name = "age")
    val age: Int,
    @Column(name = "gender")
    val gender: String,

    @Column(name = "created_at")
    val createdAt: LocalDateTime? = null,

    @Column(name = "profile_picture")
    val profilePicture: String? = null, // Can be a file path or URL

    @OneToOne(mappedBy = "user", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    @JsonManagedReference
    val medicalInfo: MedicalInfo? = null
)
