package com.ecgapp.ecgapp.dto

// Data class for the request body
data class MedicalInfoRequest(
    val bloodType: String? = null,
    val allergies: String? = null,
    val medications: String? = null
)

public data class MedicalInfoDTO(
    val id: Long,
    val userId: Long,
    val bloodType: String?,
    val allergies: String?,
    val medications: String?
)