package com.ecgapp.ecgapp.models

import jakarta.persistence.*
import com.fasterxml.jackson.annotation.JsonBackReference

@Entity
@Table(name = "warnings")
data class Warning(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "type", nullable = false)
    val type: String,
    
    @Column(name = "details", columnDefinition = "TEXT")
    val details: String,
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ecg_recording_id")
    @JsonBackReference  // This prevents circular reference - child side (won't be serialized)
    var ecgRecording: EcgRecording? = null
)