package com.ecgapp.ecgapp.models

import jakarta.persistence.*

@Entity
@Table(name = "medical_info")
data class MedicalInfo(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int = 0,

    @Column(name = "blood_type")
    val bloodType: String? = null,

    @Column(name = "allergies")
    val allergies: String? = null,

    @Column(name = "medications")
    val medications: String? = null,

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @OneToMany(mappedBy = "medicalInfo", cascade = [CascadeType.ALL])
    val ecgRecordings: List<EcgRecording> = emptyList(),

    @OneToMany(mappedBy = "medicalInfo", cascade = [CascadeType.ALL])
    val medicalConditions: List<MedicalCondition> = emptyList(),

    @OneToMany(mappedBy = "medicalInfo", cascade = [CascadeType.ALL])
    val medicalFiles: List<MedicalFile> = emptyList(),

)
