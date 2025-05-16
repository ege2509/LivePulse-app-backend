package com.ecgapp.ecgapp.repository

import com.ecgapp.ecgapp.models.EcgRecording
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface EcgRecordingRepository : JpaRepository<EcgRecording, Long> {
    fun findByMedicalInfoId(medicalInfoUserId: Long): List<EcgRecording>

    // Optional: add this explicitly if you want, but JpaRepository already provides it
    override fun findById(id: Long): Optional<EcgRecording>
}