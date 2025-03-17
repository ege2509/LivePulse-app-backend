package com.ecgapp.ecgapp.models

import jakarta.persistence.*

@Entity
@Table(name = "medical_conditions")
data class MedicalCondition(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    val name: String,
    val description: String,
    
    @ManyToOne
    @JoinColumn(name = "medical_info_id", nullable = false)
    val medicalInfo: MedicalInfo
)