package com.ecgapp.ecgapp.models

import com.fasterxml.jackson.annotation.JsonBackReference
import com.fasterxml.jackson.annotation.JsonManagedReference

import jakarta.persistence.*

@Entity
@Table(name = "medical_info")
data class MedicalInfo(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "blood_type")
    val bloodType: String? = null,

    @Column(name = "allergies")
    val allergies: String? = null,

    @Column(name = "medications")
    val medications: String? = null,
    
    @OneToOne
    @JoinColumn(name = "user_id")
    @JsonBackReference // Child side of reference - will be excluded from serialization
    val user: User,

    @OneToMany(mappedBy = "medicalInfo", cascade = [CascadeType.ALL])
    @JsonManagedReference // Parent side for medical conditions
    val ecgRecordings: List<EcgRecording> = emptyList(),

    @OneToMany(mappedBy = "medicalInfo", cascade = [CascadeType.ALL])
    @JsonManagedReference // Parent side for medical conditions
    val medicalConditions: List<MedicalCondition> = emptyList(),

    @OneToMany(mappedBy = "medicalInfo", cascade = [CascadeType.ALL])
    @JsonManagedReference // Parent side for medical files
    val medicalFiles: List<MedicalFile> = emptyList()
)
