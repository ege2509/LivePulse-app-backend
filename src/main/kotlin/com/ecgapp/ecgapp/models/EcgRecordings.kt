package com.ecgapp.ecgapp.models

import jakarta.persistence.*
import java.time.LocalDate

@Entity
@Table(name = "ecg_recordings")
data class EcgRecording(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int = 0,
    //val filePath: String,

    @Lob
    @Column(name = "raw_data", columnDefinition = "BYTEA")
    val rawData: ByteArray,

    @Column(name = "diagnosis")
    val diagnosis: String?,

    @Column(name = "sample_rate")
    val sample_rate: Int,

    @Column(name = "num_leads")
    val num_leads: Int,

    @Column(name = "heart_rate")
    val heart_rate: Int,
    
    @ManyToOne
    @JoinColumn(name = "medical_info_id", nullable = false)
    val medicalInfo: MedicalInfo,

    @OneToMany(mappedBy = "ecgRecording", cascade = [CascadeType.ALL], orphanRemoval = true)
    val warnings: List<Warning> = mutableListOf()
)