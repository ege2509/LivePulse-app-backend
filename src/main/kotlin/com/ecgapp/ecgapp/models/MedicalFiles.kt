package com.ecgapp.ecgapp.models

import jakarta.persistence.*

@Entity
@Table(name = "medical_files")
data class MedicalFile(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    val filePath: String,
    val fileType: String,
    
    @ManyToOne
    @JoinColumn(name = "medical_info_id", nullable = false)
    val medicalInfo: MedicalInfo
)
