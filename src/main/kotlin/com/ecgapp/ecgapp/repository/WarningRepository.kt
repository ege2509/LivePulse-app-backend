package com.ecgapp.ecgapp.repository

import com.ecgapp.ecgapp.models.Warning
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface WarningRepository : JpaRepository<Warning, Long> {

    fun findByEcgRecordingId(ecgRecordingId: Int): List<Warning>

    fun findByEcgRecordingIdIn(ecgRecordingIds: List<Int>): List<Warning>

    @Query("SELECT w FROM Warning w JOIN w.ecgRecording e WHERE e.medicalInfo.id = :userId ORDER BY e.recordingDate DESC")
    fun findByUserId(@Param("userId") userId: Long): List<Warning>

    @Query("SELECT COUNT(w) FROM Warning w JOIN w.ecgRecording e WHERE e.medicalInfo.id = :userId")
    fun countByUserId(@Param("userId") userId: Long): Long

    @Query("SELECT w.type, COUNT(w) FROM Warning w JOIN w.ecgRecording e WHERE e.medicalInfo.id = :userId GROUP BY w.type")
    fun countWarningTypesByUserId(@Param("userId") userId: Long): List<Array<Any>>
}
