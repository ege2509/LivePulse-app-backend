package com.ecgapp.ecgapp.models
import java.time.LocalDate
import java.time.LocalDateTime
import com.fasterxml.jackson.annotation.JsonBackReference
import jakarta.persistence.*

@Entity
@Table(name = "medical_conditions")
data class MedicalCondition(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int = 0,

    @Column(name = "condition_name")
    val name: String,

    @Column(name = "diagnosed_date")
    val diagnosisDate: LocalDate,

    @Column(columnDefinition = "TEXT")
    val notes: String? = null,

    @Column(name = "created_at")
    val createdAt: LocalDateTime? = null,

    @Column(name = "severity")
    val severity: String,
    
    @ManyToOne
    @JoinColumn(name = "medical_info_id", nullable = false)
    @JsonBackReference 
    val medicalInfo: MedicalInfo
)