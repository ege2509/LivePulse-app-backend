package com.ecgapp.ecgapp.controllers

import com.ecgapp.ecgapp.models.Warning
import com.ecgapp.ecgapp.repository.WarningRepository
import com.ecgapp.ecgapp.repository.EcgRecordingRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/warnings")
class WarningController(
    private val warningRepository: WarningRepository,
    private val ecgRecordingRepository: EcgRecordingRepository
) {

    @GetMapping("/{ecgRecordingId}")
    fun getWarningsByEcgRecording(@PathVariable ecgRecordingId: Int): ResponseEntity<List<Warning>> {
        val warnings = warningRepository.findByEcgRecordingId(ecgRecordingId)
        return ResponseEntity.ok(warnings)
    }

        @GetMapping("/user/{userId}")
    fun getWarningsByUserId(@PathVariable userId: Long): ResponseEntity<List<Warning>> {
        return try {
            val warnings = warningRepository.findByUserId(userId)
            ResponseEntity.ok(warnings)
        } catch (e: Exception) {
            ResponseEntity.internalServerError().build()
        }
    }

    @GetMapping("/user/{userId}/count")
    fun getWarningCountForUser(@PathVariable userId: Long): ResponseEntity<Map<String, Any>> {
        val recordings = ecgRecordingRepository.findByMedicalInfoId(userId)
        
        if (recordings.isEmpty()) {
            return ResponseEntity.ok(mapOf(
                "totalWarnings" to 0,
                "recordingsWithWarnings" to 0,
                "totalRecordings" to 0
            ))
        }
        
        val recordingIds = recordings.map { it.id!! }
        val warnings = warningRepository.findByEcgRecordingIdIn(recordingIds)
        val recordingsWithWarnings = warnings.map { it.ecgRecording?.id }.distinct().size
        
        return ResponseEntity.ok(mapOf(
            "totalWarnings" to warnings.size,
            "recordingsWithWarnings" to recordingsWithWarnings,
            "totalRecordings" to recordings.size,
            "warningTypes" to warnings.groupBy { it.type }.mapValues { it.value.size }
        ))
    }

    @PostMapping
    fun addWarning(@RequestBody warning: Warning): ResponseEntity<Warning> {
        val savedWarning = warningRepository.save(warning)
        return ResponseEntity.ok(savedWarning)
    }

    @DeleteMapping("/{warningId}")
    fun deleteWarning(@PathVariable warningId: Long): ResponseEntity<Map<String, String>> {
        return if (warningRepository.existsById(warningId)) {
            warningRepository.deleteById(warningId)
            ResponseEntity.ok(mapOf("message" to "Warning deleted successfully"))
        } else {
            ResponseEntity.notFound().build()
        }
    }
}