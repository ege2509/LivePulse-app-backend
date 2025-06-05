package com.ecgapp.ecgapp.service

import com.ecgapp.ecgapp.models.MedicalInfo
import com.ecgapp.ecgapp.repository.MedicalInfoRepository
import com.ecgapp.ecgapp.repository.UserRepository
import org.springframework.stereotype.Service

@Service
class MedicalInfoService(
    private val medicalInfoRepository: MedicalInfoRepository,
    private val userRepository: UserRepository
) {
    fun getMedicalInfoByUserId(userId: Long): MedicalInfo? {
        return medicalInfoRepository.findByUserId(userId)
    }

    fun updateBloodType(userId: Long, bloodType: String): MedicalInfo? {
        val medicalInfo = medicalInfoRepository.findByUserId(userId) ?: return null
        val updatedInfo = medicalInfo.copy(bloodType = bloodType)
        return medicalInfoRepository.save(updatedInfo)
    }


    // Get user's allergies
    fun getUserAllergies(userId: Long): String? {
        return medicalInfoRepository.findByUserId(userId)?.allergies
    }

    // Update user's allergies
    fun updateUserAllergies(userId: Long, newAllergies: String): MedicalInfo? {
        val medicalInfo = medicalInfoRepository.findByUserId(userId) ?: return null
        val updatedInfo = medicalInfo.copy(allergies = newAllergies)
        return medicalInfoRepository.save(updatedInfo)
    }

    // Get user's medications
    fun getUserMedications(userId: Long): String? {
        return medicalInfoRepository.findByUserId(userId)?.medications
    }

    // Update user's medications
    fun updateUserMedications(userId: Long, newMedications: String): MedicalInfo? {
        val medicalInfo = medicalInfoRepository.findByUserId(userId) ?: return null
        val updatedInfo = medicalInfo.copy(medications = newMedications)
        return medicalInfoRepository.save(updatedInfo)
    }

    fun createMedicalInfo(
        userId: Long, 
        bloodType: String?, 
        allergies: String?, 
        medications: String?
    ): MedicalInfo? {
        return try {
            // Check if user exists
            val user = userRepository.findById(userId).orElse(null) ?: return null
            
            // Check if medical info already exists for this user
            val existingInfo = medicalInfoRepository.findByUserId(userId)
            if (existingInfo != null) {
                return null // Medical info already exists
            }

            // Create new medical info (can be empty for newly registered users)
            val medicalInfo = MedicalInfo(
                user = user,
                bloodType = bloodType,
                allergies = allergies,
                medications = medications
            )

            medicalInfoRepository.save(medicalInfo)
        } catch (e: Exception) {
            null
        }
    }
    
}
