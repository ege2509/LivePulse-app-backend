package com.ecgapp.ecgapp.repository

import com.ecgapp.ecgapp.models.EcgRecording
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface EcgRecordingRepository : JpaRepository<EcgRecording, Long> {
    fun findByMedicalInfoId(medicalInfoUserId: Long): List<EcgRecording>

    fun findById(id: Int): Optional<EcgRecording>
}