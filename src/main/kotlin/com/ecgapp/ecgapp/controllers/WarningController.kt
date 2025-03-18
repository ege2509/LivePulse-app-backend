package com.ecgapp.ecgapp.controllers

import com.ecgapp.ecgapp.models.Warning
import com.ecgapp.ecgapp.repositories.WarningRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/warnings")
class WarningController(private val warningRepository: WarningRepository) {

    @GetMapping("/{ecgRecordingId}")
    fun getWarningsByEcgRecording(@PathVariable ecgRecordingId: Long): ResponseEntity<List<Warning>> {
        val warnings = warningRepository.findByEcgRecordingId(ecgRecordingId)
        return ResponseEntity.ok(warnings)
    }

    @PostMapping
    fun addWarning(@RequestBody warning: Warning): ResponseEntity<Warning> {
        val savedWarning = warningRepository.save(warning)
        return ResponseEntity.ok(savedWarning)
    }
}
