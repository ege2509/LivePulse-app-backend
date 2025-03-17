
package com.ecgapp.ecgapp.models

import jakarta.persistence.*



@Entity
@Table(name = "ecg_recordings")
data class EcgRecording(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    val filePath: String,
    val timestamp: String,
    val warning: String?,
    
    @ManyToOne
    @JoinColumn(name = "medical_info_id", nullable = false)
    val medicalInfo: MedicalInfo
)