package com.ecgapp.ecgapp.models

import jakarta.persistence.*

@Entity
@Table(name = "warnings")
data class Warning(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val type: String,  // e.g., "AF", "Arrhythmia", "Bradycardia", etc.

    @Column(nullable = true)
    val details: String?,  // Optional details about the warning

    @ManyToOne
    @JoinColumn(name = "ecg_recording_id", nullable = false)
    val ecgRecording: EcgRecording
)