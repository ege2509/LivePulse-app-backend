package com.ecgapp.ecgapp.models

import jakarta.persistence.*
import java.time.LocalDate

@Entity
@Table(name = "ecg_recordings")
data class EcgRecording(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int = 0,
    //val filePath: String,
    val diagnosis: String?,
    
    val heart_rate: Int,
    
    @ManyToOne
    @JoinColumn(name = "medical_info_id", nullable = false)
    val medicalInfo: MedicalInfo,

    @OneToMany(mappedBy = "ecgRecording", cascade = [CascadeType.ALL], orphanRemoval = true)
    val warnings: List<Warning> = mutableListOf()
)