package com.ecgapp.ecgapp.models

import jakarta.persistence.*

@Entity
@Table(name = "medical_info")
data class MedicalInfo(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int = 0,

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
