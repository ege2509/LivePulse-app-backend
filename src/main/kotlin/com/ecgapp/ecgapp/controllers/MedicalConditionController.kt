package com.ecgapp.ecgapp.controllers

import com.ecgapp.ecgapp.models.MedicalCondition
import com.ecgapp.ecgapp.models.MedicalInfo
import com.ecgapp.ecgapp.repository.MedicalConditionRepository
import com.ecgapp.ecgapp.repository.MedicalInfoRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime
import com.ecgapp.ecgapp.dto.MedicalConditionInput
import java.time.LocalDate

@RestController
@RequestMapping("/conditions")
class MedicalConditionController(
    private val conditionRepo: MedicalConditionRepository,
    private val infoRepo: MedicalInfoRepository
) {
    // Logger for proper logging
    private val logger = org.slf4j.LoggerFactory.getLogger(MedicalConditionController::class.java)

    @GetMapping("/{id}")
    fun getConditionById(@PathVariable id: Int): ResponseEntity<MedicalCondition> {
        val condition = conditionRepo.findById(id)
        return if (condition.isPresent) ResponseEntity.ok(condition.get())
        else ResponseEntity.notFound().build()
    }

    @GetMapping("/medicalInfo/{medicalInfoId}")
    fun getConditionsByMedicalInfo(@PathVariable medicalInfoId: Long): ResponseEntity<List<MedicalCondition>> {
        return ResponseEntity.ok(conditionRepo.findByMedicalInfoId(medicalInfoId))
    }

    @PostMapping
    fun createCondition(@RequestBody newCondition: MedicalConditionInput): ResponseEntity<Any> {
        try {
            logger.info("Received request to create condition: {}", newCondition)
            
            // Check for null values
            if (newCondition.medicalInfoId == null || newCondition.conditionName.isBlank() || 
                newCondition.severity.isBlank() || newCondition.diagnosedDate.isBlank()) {
                logger.error("Required fields missing in request")
                return ResponseEntity.badRequest().body("Required fields missing")
            }
            
            // Verify medical info exists
            val medicalInfoOpt = infoRepo.findById(newCondition.medicalInfoId)
            if (medicalInfoOpt.isEmpty) {
                logger.error("MedicalInfo with ID {} not found", newCondition.medicalInfoId)
                return ResponseEntity.badRequest().body("MedicalInfo with ID ${newCondition.medicalInfoId} not found.")
            }
            
            // Parse date with detailed error handling
            val diagnosisDate = try {
                LocalDate.parse(newCondition.diagnosedDate)
            } catch (e: Exception) {
                logger.error("Error parsing date: {}. Error: {}", newCondition.diagnosedDate, e.message)
                return ResponseEntity.badRequest()
                    .body("Invalid date format for '${newCondition.diagnosedDate}'. Please use YYYY-MM-DD format.")
            }
            
            // Build the medical condition
            logger.info("Creating medical condition entity")
            val medicalCondition = MedicalCondition(
                name = newCondition.conditionName,
                severity = newCondition.severity,
                diagnosisDate = diagnosisDate,
                notes = newCondition.notes,
                createdAt = LocalDateTime.now(),
                medicalInfo = medicalInfoOpt.get()
            )
            
            // Save with detailed error logging
            try {
                logger.info("Saving medical condition to database")
                val savedCondition = conditionRepo.save(medicalCondition)
                logger.info("Successfully saved medical condition with ID: {}", savedCondition.id)
                return ResponseEntity.ok(savedCondition)
            } catch (e: Exception) {
                logger.error("Database error saving condition: {}", e.message, e)
                return ResponseEntity.internalServerError()
                    .body("Database error: ${e.message}")
            }
        } catch (e: Exception) {
            // General error handling
            logger.error("Unexpected error in createCondition: {}", e.message, e)
            return ResponseEntity.internalServerError()
                .body("Unexpected error: ${e.message}")
        }
    }
}
