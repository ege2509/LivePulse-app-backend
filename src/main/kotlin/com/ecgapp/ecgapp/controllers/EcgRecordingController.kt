package com.ecgapp.ecgapp.controllers

import com.ecgapp.ecgapp.models.EcgRecording
import com.ecgapp.ecgapp.repository.EcgRecordingRepository
import com.ecgapp.ecgapp.services.EcgProcessingService
import com.ecgapp.ecgapp.service.RealtimeEcgService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.time.format.DateTimeFormatter

@RestController
@RequestMapping("/api/ecg")
class EcgController(
    private val ecgProcessingService: EcgProcessingService,
    private val realtimeEcgService: RealtimeEcgService,
    private val ecgRecordingRepository: EcgRecordingRepository
) {

    private val dateFormatter = DateTimeFormatter.ISO_DATE_TIME

    @PostMapping("/upload")
    suspend fun uploadEcgData(
        @RequestParam file: MultipartFile,
        @RequestParam userId: Long
    ): ResponseEntity<Map<String, Any>> {
        val recording = ecgProcessingService.processRawData(file.inputStream, userId)
        
        return ResponseEntity.ok(mapOf(
            "id" to recording.id,
            "heartRate" to recording.heartRate,
            "recordingDate" to recording.recordingDate.format(dateFormatter)
        ))
    }
    
    @GetMapping("/recordings")
    suspend fun getUserRecordings(@RequestParam userId: Long): ResponseEntity<List<Map<String, Any>>> {
        val recordings = ecgRecordingRepository.findByMedicalInfoId(userId)
        
        val response = recordings.map { recording ->
            mapOf(
                "id" to recording.id,
                "heartRate" to recording.heartRate,
                "recordingDate" to recording.recordingDate.format(dateFormatter),
                "diagnosis" to (recording.diagnosis ?: "No diagnosis")
            )
        }
        
        return ResponseEntity.ok(response)
    }
    
    @GetMapping("/recording/{id}")
    suspend fun getRecording(@PathVariable id: Long): ResponseEntity<EcgRecording> {
        val recordings = ecgRecordingRepository.findByMedicalInfoId(id)
        if (recordings.isEmpty()) {
            return ResponseEntity.notFound().build()
        }
            
        return ResponseEntity.ok(recordings.first())
    }
    
    // Server-Sent Events endpoint for clients that can't use WebSockets
    @GetMapping("/monitor/{userId}", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun monitorEcg(@PathVariable userId: Long): Flow<ServerSentEvent<String>> {
        return realtimeEcgService.getEcgDataFlow()
            .map { packet ->
                if (packet.userId == userId) {
                    val data = """
                        {
                            "timestamp": ${packet.timestamp},
                            "data": [${packet.ecgData.joinToString(",")}],
                            "heartRate": ${packet.heartRate ?: 0}
                        }
                    """.trimIndent()
                    
                    ServerSentEvent.builder<String>()
                        .id(packet.timestamp.toString())
                        .event("ecg-data")
                        .data(data)
                        .build()
                } else {
                    ServerSentEvent.builder<String>()
                        .comment("Heartbeat")
                        .build()
                }
            }
    }
    
}