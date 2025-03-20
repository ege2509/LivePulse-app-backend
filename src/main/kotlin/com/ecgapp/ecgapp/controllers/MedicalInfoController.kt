package com.ecgapp.ecgapp.controllers

import com.ecgapp.ecgapp.service.MedicalInfoService
import com.ecgapp.ecgapp.service.UserService
import com.ecgapp.ecgapp.repository.MedicalInfoRepository
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import com.ecgapp.ecgapp.dto.MedicalInfoRequest

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
        ResponseEntity.status(HttpStatus.CREATED).body(savedInfo)
    } else {
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(mapOf("error" to "Unable to create medical info. User may not exist or info already exists."))
    }
}

    @GetMapping("/{userId}/medical-info")
    fun getMedicalInfo(@PathVariable userId: Long): ResponseEntity<Any> {
        val info = medicalInfoService.getMedicalInfoByUserId(userId)
        return if (info != null) ResponseEntity.ok(info)
        else ResponseEntity.notFound().build()
    }

    @GetMapping("/blood_type")
    fun getBloodType(@RequestParam userId: Long): ResponseEntity<String> {
        val info = medicalInfoService.getMedicalInfoByUserId(userId)
        return if (info?.bloodType != null)
            ResponseEntity.ok(info.bloodType)
        else
            ResponseEntity.notFound().build()
    }

    @PostMapping("/blood_type")
    fun setBloodType(
        @RequestParam userId: Long,
        @RequestParam bloodType: String
    ): ResponseEntity<Any> {
        val updated = medicalInfoService.updateBloodType(userId, bloodType)
        return if (updated != null) ResponseEntity.ok(updated)
        else ResponseEntity.notFound().build()
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
            ResponseEntity.ok("Allergies updated successfully")
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