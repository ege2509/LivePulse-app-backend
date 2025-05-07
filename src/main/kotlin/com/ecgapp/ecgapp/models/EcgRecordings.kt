package com.ecgapp.ecgapp.models

import jakarta.persistence.*
import java.time.LocalDateTime
import com.fasterxml.jackson.annotation.JsonBackReference

@Entity
@Table(name = "ecg_recordings")
data class EcgRecording(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int = 0,

    @Lob
    @Column(name = "raw_data", columnDefinition = "BYTEA")
    val rawData: ByteArray,

    @Column(name = "processed_data", columnDefinition = "TEXT")
    val processedData: String? = null,

    @Column(name = "diagnosis")
    val diagnosis: String? = null,

    @Column(name = "sample_rate")
    val sampleRate: Int,

    @Column(name = "num_leads")
    val numLeads: Int,

    @Column(name = "heart_rate")
    val heartRate: Int,
    
    @Column(name = "qrs_complexes", columnDefinition = "TEXT")
    val qrsComplexes: String? = null,
    
    @Column(name = "recording_date")
    val recordingDate: LocalDateTime = LocalDateTime.now(),
    
    @ManyToOne
    @JoinColumn(name = "medical_info_id", nullable = false)
    @JsonBackReference // Child side of reference - will be excluded from serialization
    val medicalInfo: MedicalInfo,

    @OneToMany(mappedBy = "ecgRecording", cascade = [CascadeType.ALL], orphanRemoval = true)
    val warnings: List<Warning> = mutableListOf()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EcgRecording

        if (id != other.id) return false
        if (!rawData.contentEquals(other.rawData)) return false
        if (processedData != other.processedData) return false
        if (diagnosis != other.diagnosis) return false
        if (sampleRate != other.sampleRate) return false
        if (numLeads != other.numLeads) return false
        if (heartRate != other.heartRate) return false
        if (qrsComplexes != other.qrsComplexes) return false
        if (recordingDate != other.recordingDate) return false
        if (medicalInfo != other.medicalInfo) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + rawData.contentHashCode()
        result = 31 * result + (processedData?.hashCode() ?: 0)
        result = 31 * result + (diagnosis?.hashCode() ?: 0)
        result = 31 * result + sampleRate
        result = 31 * result + numLeads
        result = 31 * result + heartRate
        result = 31 * result + (qrsComplexes?.hashCode() ?: 0)
        result = 31 * result + recordingDate.hashCode()
        result = 31 * result + medicalInfo.hashCode()
        return result
    }
}