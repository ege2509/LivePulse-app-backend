package com.ecgapp.ecgapp.dto

import java.time.LocalDateTime



data class MedicalFileResponseDTO(
    val id: Long,
    val fileName: String,
    val fileUrl: String,
    val uploadedAt: LocalDateTime,
    val medicalInfoId: Long
)

data class MedicalFileUploadDTO(
    val fileName: String,
    val fileUrl: String,
    val medicalInfoId: Long
)
