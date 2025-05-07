package com.ecgapp.ecgapp.controllers

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime
import com.ecgapp.ecgapp.repository.MedicalInfoRepository
import com.ecgapp.ecgapp.repository.MedicalFileRepository
import com.ecgapp.ecgapp.models.MedicalFile
import com.ecgapp.ecgapp.models.MedicalInfo
import com.ecgapp.ecgapp.dto.MedicalFileResponseDTO
import com.ecgapp.ecgapp.dto.MedicalFileUploadDTO

@RestController
@RequestMapping("/api/v1/medical-files")
class MedicalFileController(
    val medicalFileRepository: MedicalFileRepository,
    val medicalInfoRepository: MedicalInfoRepository
) {

    @GetMapping("/user/{userId}")
    fun getAllFiles(): ResponseEntity<List<MedicalFileResponseDTO>> {
        val allFiles = medicalFileRepository.findAll()
        
        val response = allFiles.map {
            MedicalFileResponseDTO(
                id = it.id,
                fileName = it.name,
                fileUrl = it.filePath,
                uploadedAt = it.uploadedAt ?: LocalDateTime.now(),
                medicalInfoId = it.medicalInfo.id
            )
        }
        
        return ResponseEntity.ok(response)
    }

    @PostMapping
    fun uploadFile(@RequestBody request: MedicalFileUploadDTO): ResponseEntity<Any> {
        // Now request.medicalInfoId is Long, so there's no type mismatch
        val medicalInfo = medicalInfoRepository.findByUserId(request.medicalInfoId)
        if (medicalInfo == null) {
            return ResponseEntity.badRequest().body("MedicalInfo not found for user ID: ${request.medicalInfoId}")
        }

        val file = MedicalFile(
            name = request.fileName,
            filePath = request.fileUrl,
            uploadedAt = LocalDateTime.now(),
            medicalInfo = medicalInfo
        )

        val savedFile = medicalFileRepository.save(file)

        return ResponseEntity.ok(
            MedicalFileResponseDTO(
                id = savedFile.id,
                fileName = savedFile.name,
                fileUrl = savedFile.filePath,
                uploadedAt = savedFile.uploadedAt ?: LocalDateTime.now(),
                medicalInfoId = savedFile.medicalInfo.id
            )
        )
    }

    @GetMapping("/medical-info/{medicalInfoId}")
    fun getFilesByMedicalInfo(@PathVariable medicalInfoId: Long): ResponseEntity<List<MedicalFileResponseDTO>> {
        val files = medicalFileRepository.findByMedicalInfoId(medicalInfoId)
        val response = files.map {
            MedicalFileResponseDTO(
                id = it.id,
                fileName = it.name,
                fileUrl = it.filePath,
                uploadedAt = it.uploadedAt ?: LocalDateTime.now(),
                medicalInfoId = it.medicalInfo.id
            )
        }
        return ResponseEntity.ok(response)
    }
}