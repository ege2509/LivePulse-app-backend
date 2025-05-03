package com.ecgapp.ecgapp.repository

import com.ecgapp.ecgapp.models.EcgRecording
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface EcgRecordingRepository : JpaRepository<EcgRecording, Int> {
    fun findByMedicalInfoId(medicalInfoUserId: Long): List<EcgRecording>
}
