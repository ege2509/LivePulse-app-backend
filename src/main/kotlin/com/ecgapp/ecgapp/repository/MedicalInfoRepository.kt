package com.ecgapp.ecgapp.repository

import com.ecgapp.ecgapp.models.MedicalInfo
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface MedicalInfoRepository : JpaRepository<MedicalInfo, Int> {
    fun findByUserId(userId: Long): MedicalInfo?
}

