package com.ecgapp.ecgapp.repositories

import com.ecgapp.ecgapp.models.Warning
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface WarningRepository : JpaRepository<Warning, Long> {
    fun findByEcgRecordingId(ecgRecordingId: Long): List<Warning>
}
