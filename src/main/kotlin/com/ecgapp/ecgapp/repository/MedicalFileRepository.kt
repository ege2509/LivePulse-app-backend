package com.ecgapp.ecgapp.repository

import org.springframework.data.jpa.repository.JpaRepository
import com.ecgapp.ecgapp.models.MedicalFile

interface MedicalFileRepository : JpaRepository<MedicalFile, Long> {
    fun findByMedicalInfoId(medicalInfoId: Long): List<MedicalFile>
}
