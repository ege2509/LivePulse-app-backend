package com.ecgapp.ecgapp.controllers

import com.ecgapp.ecgapp.service.MedicalInfoService
import com.ecgapp.ecgapp.service.UserService
import com.ecgapp.ecgapp.repository.MedicalInfoRepository
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import com.ecgapp.ecgapp.dto.MedicalInfoRequest
import com.ecgapp.ecgapp.dto.MedicalInfoDTO

@RestController
@RequestMapping("/users")
class MedicalInfoController(
    private val medicalInfoService: MedicalInfoService,
    private val userService: UserService,
    private val medicalInfoRepository: MedicalInfoRepository
) {

    @PostMapping("/{userId}/medical-info")
    fun createMedicalInfo(
        @PathVariable userId: Long,
        @RequestBody medicalInfoRequest: MedicalInfoRequest
    ): ResponseEntity<Any> {
        val savedInfo = medicalInfoService.createMedicalInfo(
            userId,
            medicalInfoRequest.bloodType,
            medicalInfoRequest.allergies,
            medicalInfoRequest.medications
        )
    
        return if (savedInfo != null) {
            val dto = MedicalInfoDTO(
                id = savedInfo.id,
                userId = savedInfo.user.id,
                bloodType = savedInfo.bloodType,
                allergies = savedInfo.allergies,
                medications = savedInfo.medications
            )
            ResponseEntity.status(HttpStatus.CREATED).body(dto)
        } else {
            ResponseEntity.status(HttpStatus.CONFLICT)
                .body(mapOf("error" to "Unable to create medical info. User may not exist or info already exists."))
        }
    }

    @GetMapping("/{userId}/medical-info")
    fun getMedicalInfo(@PathVariable userId: Long): ResponseEntity<Any> {
        val info = medicalInfoService.getMedicalInfoByUserId(userId)
        return if (info != null) {
            val dto = MedicalInfoDTO(
                id = info.id,
                userId = info.user.id,
                bloodType = info.bloodType,
                allergies = info.allergies,
                medications = info.medications
            )
            ResponseEntity.ok(dto)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @GetMapping("/blood_type")
    fun getBloodType(@RequestParam userId: Long): ResponseEntity<MedicalInfoDTO> {
        val info = medicalInfoService.getMedicalInfoByUserId(userId)
        return if (info != null) {
            val dto = MedicalInfoDTO(
                id = info.id, // Added missing id
                userId = info.user.id,
                bloodType = info.bloodType,
                allergies = info.allergies,
                medications = info.medications
            )
            ResponseEntity.ok(dto)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @PostMapping("/blood_type")
    fun setBloodType(
        @RequestParam userId: Long,
        @RequestParam bloodType: String
    ): ResponseEntity<MedicalInfoDTO> {
        val updated = medicalInfoService.updateBloodType(userId, bloodType)
        return if (updated != null) {
            val dto = MedicalInfoDTO(
                id = updated.id, // Added missing id
                userId = updated.user.id,
                bloodType = updated.bloodType,
                allergies = updated.allergies,
                medications = updated.medications
            )
            ResponseEntity.ok(dto)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @GetMapping("/allergies")
    fun getUserAllergies(@RequestParam userId: Long): ResponseEntity<String> {
        val allergies = medicalInfoService.getUserAllergies(userId)
        return if (allergies != null) {
            ResponseEntity.ok(allergies)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @PostMapping("/allergies")
    fun updateUserAllergies(@RequestParam userId: Long, @RequestBody newAllergies: String): ResponseEntity<String> {
        val updatedInfo = medicalInfoService.updateUserAllergies(userId, newAllergies)
        return if (updatedInfo != null) {
            ResponseEntity.ok("Allergies added successfully")
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @GetMapping("/medications")
    fun getUserMedications(@RequestParam userId: Long): ResponseEntity<String> {
        val medications = medicalInfoService.getUserMedications(userId)
        return if (medications != null) {
            ResponseEntity.ok(medications)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @PostMapping("/medications")
    fun updateUserMedications(@RequestParam userId: Long, @RequestBody newMedications: String): ResponseEntity<String> {
        val updatedInfo = medicalInfoService.updateUserMedications(userId, newMedications)
        return if (updatedInfo != null) {
            ResponseEntity.ok("Medications updated successfully")
        } else {
            ResponseEntity.notFound().build()
        }
    }
}