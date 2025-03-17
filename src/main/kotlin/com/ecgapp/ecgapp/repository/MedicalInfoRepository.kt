package com.ecgapp.ecgapp.repository

import com.ecgapp.ecgapp.models.MedicalInfo
import org.springframework.data.jpa.repository.JpaRepository

interface MedicalInfoRepository : JpaRepository<MedicalInfo, Long>
