package com.ecgapp.ecgapp.models
import java.time.LocalDateTime
import com.fasterxml.jackson.annotation.JsonBackReference
import jakarta.persistence.*

@Entity
@Table(name = "medical_files")
data class MedicalFile(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "file_name")
    val name: String,

    @Column(name = "file_url")
    val filePath: String,

    @Column(name = "uploaded_at")
    val uploadedAt: LocalDateTime? = null,

    @ManyToOne
    @JoinColumn(name = "medical_info_id", nullable = false)
    @JsonBackReference // Child side of reference - will be excluded from serialization
    val medicalInfo: MedicalInfo
)