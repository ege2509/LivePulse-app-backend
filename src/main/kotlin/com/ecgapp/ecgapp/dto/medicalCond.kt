package com.ecgapp.ecgapp.dto

import java.time.LocalDate

data class MedicalConditionInput(
    val medicalInfoId: Int,
    val conditionName: String,
    val severity: String,
    val diagnosedDate: String,
    val notes: String?
)