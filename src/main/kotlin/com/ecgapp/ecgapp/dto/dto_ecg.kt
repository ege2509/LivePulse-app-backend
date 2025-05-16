package com.ecgapp.ecgapp.dto


import java.time.LocalDateTime
import com.ecgapp.ecgapp.models.EcgRecording

data class EcgRecordingDto(
    val id: Int,
    val medicalInfoId: Long,
    val heartRate: Int,
    val diagnosis: String?,
    //val rawData: ByteArray,
    val sampleRate: Int,
    val numLeads: Int,
    val recordingDate: LocalDateTime,
    val processedData: String?
)

fun EcgRecording.toDto(): EcgRecordingDto = EcgRecordingDto(
    id = this.id,
    medicalInfoId = this.medicalInfo.id,
    heartRate = this.heartRate,
    diagnosis = this.diagnosis,
    //rawData = this.rawData,
    sampleRate = this.sampleRate,
    numLeads = this.numLeads,
    recordingDate = this.recordingDate,
    processedData = this.processedData
)
