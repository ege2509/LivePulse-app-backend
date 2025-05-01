package com.ecgapp.ecgapp.controllers

import com.ecgapp.ecgapp.models.EcgRecording
import com.ecgapp.ecgapp.repository.EcgRecordingRepository
import org.springframework.web.bind.annotation.*
import org.springframework.http.ResponseEntity

@RestController
@RequestMapping("/api/ecg")
class EcgRecordingController(private val ecgRepo: EcgRecordingRepository) {

    @PostMapping
    fun saveEcgRecording(@RequestBody ecgRecording: EcgRecording): ResponseEntity<EcgRecording> {
        val saved = ecgRepo.save(ecgRecording)
        return ResponseEntity.ok(saved)
    }

    @GetMapping("/{id}")
    fun getEcgRecording(@PathVariable id: Int): ResponseEntity<EcgRecording> {
        val found = ecgRepo.findById(id)
        return if (found.isPresent) ResponseEntity.ok(found.get())
        else ResponseEntity.notFound().build()
    }
}
