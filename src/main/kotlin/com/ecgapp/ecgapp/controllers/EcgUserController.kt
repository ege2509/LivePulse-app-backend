package com.ecgapp.ecgapp.controllers

import com.ecgapp.ecgapp.models.EcgRecording
import com.ecgapp.ecgapp.repository.EcgRecordingRepository
import com.ecgapp.ecgapp.service.ActiveEcgUserService
import com.ecgapp.ecgapp.service.RealtimeEcgService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.time.format.DateTimeFormatter
import com.ecgapp.ecgapp.dto.EcgRecordingDto
import com.ecgapp.ecgapp.dto.toDto
import org.springframework.stereotype.Service 

@RestController
@RequestMapping("/apiUser")
class EcgUserController(private val activeEcgUserService: ActiveEcgUserService) {
    
    @PostMapping("/set-active-ecg-user")
    fun setActiveEcgUser(@RequestBody request: Map<String, Long>): ResponseEntity<String> {
        val userId = request["userId"]
        return if (userId != null) {
            activeEcgUserService.setActiveUser(userId)
            ResponseEntity.ok("Active ECG user set to $userId")
        } else {
            ResponseEntity.badRequest().body("Missing userId")
        }
    }
}