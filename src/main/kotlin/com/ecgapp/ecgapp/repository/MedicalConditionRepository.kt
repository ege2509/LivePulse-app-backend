package com.ecgapp.ecgapp.repository

import com.ecgapp.ecgapp.models.MedicalCondition
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface MedicalConditionRepository : JpaRepository<MedicalCondition, Int> {
    fun findByMedicalInfoId(medicalInfoId: Long): List<MedicalCondition>
}