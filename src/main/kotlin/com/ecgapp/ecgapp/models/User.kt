package com.ecgapp.ecgapp.models


import jakarta.persistence.*

@Entity
@Table(name = "users")
data class User(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, unique = true)
    val email: String,

    @Column(nullable = false)
    val password: String,

    val age: Int,
    val gender: String,
    val profilePicture: String? // Can be a file path or URL

    @OneToOne(mappedBy = "user", cascade = [CascadeType.ALL])
    val medicalInfo: MedicalInfo? = null
)
