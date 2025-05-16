package com.ecgapp.ecgapp.controller

import com.ecgapp.ecgapp.service.EcgDiagnosticObserver
import com.ecgapp.ecgapp.service.RealtimeEcgService
import com.ecgapp.ecgapp.service.RealtimeEcgService.EcgDataPacket
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import jakarta.annotation.PostConstruct

@RestController
@RequestMapping("/api/ecg")
class EcgTestController : EcgDiagnosticObserver {
    
    @Autowired
    private lateinit var realtimeEcgService: RealtimeEcgService
    
    // Store active connections for monitoring
    private val activeConnections = ConcurrentHashMap<Long, ConnectionDiagnostics>()
    private val packetCounter = AtomicInteger(0)
    private val testInProgress = AtomicBoolean(false)

    
    // Register this controller as an observer after construction
    @PostConstruct
    fun init() {
        realtimeEcgService.registerDiagnosticObserver(this)
    }
    
    data class ConnectionDiagnostics(
        val userId: Long,
        val connectionTime: Long = System.currentTimeMillis(),
        var lastPacketTime: Long = System.currentTimeMillis(),
        var packetsSent: Int = 0,
        var lastHeartRate: Int = 0,
        var lastAbnormality: String = "None",
        var errors: MutableList<String> = mutableListOf()
    )

    /**
     * Implementation of EcgDiagnosticObserver.registerConnection
     */
    override fun registerConnection(userId: Long) {
        activeConnections.computeIfAbsent(userId) { 
            ConnectionDiagnostics(userId) 
        }
    }

    /**
     * Implementation of EcgDiagnosticObserver.unregisterConnection
     */
    override fun unregisterConnection(userId: Long) {
        activeConnections.remove(userId)
    }

    /**
     * Implementation of EcgDiagnosticObserver.updateConnectionStats
     */
    override fun updateConnectionStats(userId: Long, packet: RealtimeEcgService.EcgDataPacket) {
        val diagnostics = activeConnections.computeIfAbsent(userId) { 
            ConnectionDiagnostics(userId) 
        }
        
        diagnostics.lastPacketTime = System.currentTimeMillis()
        diagnostics.packetsSent++
        diagnostics.lastHeartRate = packet.heartRate
        diagnostics.lastAbnormality = packet.abnormalities
            .maxByOrNull { it.value }?.key ?: "None"
    }

    @GetMapping("/websocket/status")
    fun getWebSocketStatus(): ResponseEntity<Map<String, Any>> {
        // Get the active sessions from the RealtimeEcgService
        val activeSessions = activeConnections.size
        val activeUsers = activeConnections.keys.size
        
        return ResponseEntity.ok(mapOf(
            "status" to "active",
            "activeSessions" to activeSessions,
            "activeUsers" to activeUsers,
            "testInProgress" to testInProgress.get(),
            "connections" to activeConnections.map { (userId, diag) ->
                mapOf(
                    "userId" to userId,
                    "lastActive" to (System.currentTimeMillis() - diag.lastPacketTime),
                    "packetsSent" to diag.packetsSent,
                    "lastHeartRate" to diag.lastHeartRate,
                    "lastAbnormality" to diag.lastAbnormality
                )
            },
            "timestamp" to System.currentTimeMillis()
        ))
    }
    
    /**
     * Simple ping endpoint to test REST API connectivity
     */
    @GetMapping("/ping")
    fun ping(): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(mapOf(
            "status" to "ok",
            "timestamp" to System.currentTimeMillis(),
            "message" to "ECG Diagnostic API is running"
        ))
    }
    
    /**
     * Enhanced diagnostic packet sender
     */
    /*@PostMapping("/test/packet/{userId}")
    fun sendTestPacket(
        @PathVariable userId: Long,
        @RequestParam(required = false, defaultValue = "75") heartRate: Int,
        @RequestParam(required = false, defaultValue = "false") abnormal: Boolean,
        @RequestParam(required = false, defaultValue = "false") verbose: Boolean
    ): ResponseEntity<Map<String, Any>> {
        val diagnosticId = UUID.randomUUID().toString()
        val result = mutableMapOf<String, Any>(
            "diagnosticId" to diagnosticId,
            "userId" to userId,
            "heartRate" to heartRate,
            "abnormal" to abnormal
        )
        
        try {
            val testData = generateTestEcgData(abnormal)
            
            val abnormalities = if (abnormal) {
                mapOf(
                    "RBBB" to 0.82f,
                    "LBBB" to 0.1f,
                    "SB" to if (heartRate < 60) 0.85f else 0.05f,
                    "AF" to 0.15f,
                    "ST" to if (heartRate > 100) 0.85f else 0.0f,
                    "1dAVb" to 0.0f
                )
            } else {
                mapOf(
                    "RBBB" to 0.05f,
                    "LBBB" to 0.01f,
                    "SB" to 0.02f,
                    "AF" to 0.03f,
                    "ST" to 0.0f,
                    "1dAVb" to 0.0f
                )
            }
            
            val packet = EcgDataPacket (
                userId,
                System.currentTimeMillis(),
                testData,
                heartRate,
                abnormalities,
                sampleRate,
                overlapPoints
            )
            
            // Collect pre-send diagnostics
            val preSendTime = System.currentTimeMillis()
            result["preSendTime"] = preSendTime
            
            // Send packet
            runBlocking {
                realtimeEcgService.sendDirectPacket(userId, packet)
            }
            
            // Update diagnostics
            val postSendTime = System.currentTimeMillis()
            result["postSendTime"] = postSendTime
            result["sendDurationMs"] = postSendTime - preSendTime
            
            // Update connection diagnostics
            val diagnostics = activeConnections.computeIfAbsent(userId) { 
                ConnectionDiagnostics(userId) 
            }
            diagnostics.lastPacketTime = postSendTime
            diagnostics.packetsSent++
            diagnostics.lastHeartRate = heartRate
            diagnostics.lastAbnormality = if (abnormal) {
                abnormalities.maxByOrNull { it.value }?.key ?: "None"
            } else "None"
            
            // Packet details
            packetCounter.incrementAndGet()
            
            // Verbose response includes full packet details
            if (verbose) {
                result["packetDetails"] = mapOf(
                    "dataSamples" to testData[1].size,
                    "leadCount" to testData.size,
                    "abnormalities" to abnormalities
                )
            }
            
            result["success"] = true
            result["packetId"] = packetCounter.get()
            result["message"] = "Test packet sent successfully"
            
            return ResponseEntity.ok(result)
            
        } catch (e: Exception) {
            e.printStackTrace()
            
            // Add error to diagnostics
            val diagnostics = activeConnections.computeIfAbsent(userId) { 
                ConnectionDiagnostics(userId) 
            }
            diagnostics.errors.add("${e.javaClass.simpleName}: ${e.message}")
            
            result["success"] = false
            result["error"] = e.message ?: "Unknown error"
            result["errorType"] = e.javaClass.simpleName
            result["message"] = "Error sending test packet"
            
            return ResponseEntity.internalServerError().body(result)
        }
    }*/
    
    /**
     * Enhanced continuous test with progress tracking
     
    @PostMapping("/test/continuous/{userId}")
    fun startContinuousTest(
        @PathVariable userId: Long,
        @RequestParam(required = false, defaultValue = "15") seconds: Int,
        @RequestParam(required = false, defaultValue = "150") intervalMs: Int,
        @RequestParam(required = false, defaultValue = "false") includeAbnormal: Boolean
    ): ResponseEntity<Map<String, Any>> {
        
        if (testInProgress.get()) {
            return ResponseEntity.badRequest().body(mapOf(
                "success" to false,
                "message" to "A test is already in progress. Wait for it to complete or check active connections."
            ))
        }
        
        return try {
            testInProgress.set(true)
            
            // Calculate packets to send
            val packetCount = (seconds * 1000) / intervalMs
            val testId = UUID.randomUUID().toString()
            
            // Create initial diagnostics
            val diagnostics = activeConnections.computeIfAbsent(userId) { 
                ConnectionDiagnostics(userId) 
            }
            
            // Start in a new thread
            Thread {
                try {
                    for (i in 0 until packetCount) {
                        // Determine if this packet should be abnormal
                        val abnormal = includeAbnormal && i > packetCount / 2
                        
                        // Customize heart rate based on position in sequence
                        val heartRate = when {
                            abnormal -> 55 // Bradycardia 
                            i % 20 == 0 -> 85 // Occasional spike
                            else -> 72 // Normal
                        }
                        
                        // Generate and send data
                        val testData = generateTestEcgData(abnormal)
                        
                        // Create abnormalities based on heart rate and position
                        val abnormalities = if (abnormal) {
                            mapOf(
                                "RBBB" to 0.82f,
                                "LBBB" to 0.1f,
                                "SB" to if (heartRate < 60) 0.85f else 0.05f,
                                "AF" to 0.15f,
                                "ST" to if (heartRate > 100) 0.85f else 0.0f,
                                "1dAVb" to 0.0f
                            )
                        } else {
                            mapOf(
                                "RBBB" to 0.05f,
                                "LBBB" to 0.01f,
                                "SB" to 0.02f,
                                "AF" to 0.03f,
                                "ST" to 0.0f,
                                "1dAVb" to 0.0f
                            )
                        }
                        
                        // Create and send packet
                        val packet = EcgDataPacket(
                                userId,
                                System.currentTimeMillis(),
                                testData,
                                heartRate,
                                abnormalities,
                                sampleRate,
                                overlapPoints
                            )
                        
                        runBlocking {
                            realtimeEcgService.sendDirectPacket(userId, packet)
                        }
                        
                        // Update diagnostics
                        synchronized(diagnostics) {
                            diagnostics.lastPacketTime = System.currentTimeMillis()
                            diagnostics.packetsSent++
                            diagnostics.lastHeartRate = heartRate
                            diagnostics.lastAbnormality = if (abnormal) {
                                abnormalities.maxByOrNull { it.value }?.key ?: "None"
                            } else "None"
                        }
                        
                        // Sleep for interval
                        Thread.sleep(intervalMs.toLong())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    synchronized(diagnostics) {
                        diagnostics.errors.add("Test ${testId} error: ${e.message}")
                    }
                } finally {
                    testInProgress.set(false)
                }
            }.apply {
                name = "ECG-Test-$testId"
                isDaemon = true
                start()
            }
            
            ResponseEntity.ok(mapOf(
                "success" to true,
                "message" to "Started continuous test",
                "testId" to testId,
                "userId" to userId,
                "seconds" to seconds,
                "intervalMs" to intervalMs,
                "expectedPackets" to packetCount,
                "includeAbnormal" to includeAbnormal
            ))
            
        } catch (e: Exception) {
            testInProgress.set(false)
            e.printStackTrace()
            ResponseEntity.internalServerError().body(mapOf(
                "success" to false,
                "message" to "Error starting continuous test: ${e.message}",
                "error" to (e.message ?: "Unknown error")
            ))
        }
    }*/
    
    /**
     * Enhanced connections endpoint with detailed diagnostics
     */
    @GetMapping("/test/connections", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getActiveConnections(): ResponseEntity<Map<String, Any>> {
        // Clean up stale connections (older than 30 seconds)
        val currentTime = System.currentTimeMillis()
        val staleThreshold = 30000L
        
        val staleConnections = activeConnections.filter { (_, diagnostics) ->
            currentTime - diagnostics.lastPacketTime > staleThreshold
        }.keys
        
        staleConnections.forEach { activeConnections.remove(it) }
        
        // Build response with detailed diagnostics
        val activeDetails = activeConnections.map { (userId, diagnostics) ->
            mapOf(
                "userId" to userId,
                "connectedSince" to diagnostics.connectionTime,
                "lastActivityMs" to (currentTime - diagnostics.lastPacketTime),
                "packetsSent" to diagnostics.packetsSent,
                "lastHeartRate" to diagnostics.lastHeartRate,
                "lastAbnormality" to diagnostics.lastAbnormality,
                "errors" to diagnostics.errors
            )
        }
        
        return ResponseEntity.ok(mapOf(
            "activeCount" to activeConnections.size,
            "testInProgress" to testInProgress.get(),
            "timestamp" to currentTime,
            "connections" to activeDetails
        ))
    }

    
    /**
     * Generate test ECG data for all leads
     */
    private fun generateTestEcgData(abnormal: Boolean): Array<FloatArray> {
        // Create a realistic ECG waveform pattern for each lead
        return Array(12) { lead ->
            FloatArray(20) { i ->
                // Base pattern values resembling PQRST complex
                val basePattern = when (i) {
                    0, 1, 19 -> 0.0f  // Baseline
                    2, 3 -> 0.1f + (lead % 3) * 0.05f  // P wave
                    4 -> 0.0f  // PR segment
                    5 -> -0.1f  // Q wave
                    6 -> -0.2f * (1 + lead % 2)  // Deeper Q for some leads
                    7 -> 0.0f  // Q-R segment
                    8 -> 1.0f + lead * 0.2f  // R wave
                    9 -> 1.5f + lead * 0.2f  // R peak
                    10 -> 0.5f  // R-S segment
                    11 -> -0.5f - (lead % 3) * 0.2f  // S wave
                    12 -> -0.3f  // S wave end
                    13 -> 0.0f  // ST segment
                    14 -> 0.2f  // T wave start
                    15 -> 0.4f + (lead % 2) * 0.1f  // T wave peak
                    16 -> 0.2f  // T wave end
                    17, 18 -> 0.0f  // TP segment
                    else -> 0.0f
                }
                
                // Apply lead-specific adjustments
                val leadFactor = when (lead) {
                    0 -> 1.0f  // Lead I
                    1 -> 1.2f  // Lead II
                    2 -> 0.7f  // Lead III
                    3 -> -0.5f  // aVR
                    4 -> 0.8f  // aVL
                    5 -> 1.1f  // aVF
                    6 -> 1.7f  // V1
                    7 -> 2.0f  // V2
                    8 -> 2.5f  // V3
                    9 -> 2.2f  // V4
                    10 -> 1.8f  // V5
                    11 -> 1.5f  // V6
                    else -> 1.0f
                }
                
                // For abnormal patterns, modify the waves
                val abnormalityFactor = if (abnormal) {
                    when (lead) {
                        6, 7 -> { // V1, V2 - make RSR' for RBBB simulation
                            if (i == 10) 0.8f else if (i == 11) 1.2f else 1.0f
                        }
                        0, 1 -> { // Leads I, II - wider S wave for RBBB
                            if (i == 11 || i == 12) 1.3f else 1.0f
                        }
                        else -> 1.0f
                    }
                } else 1.0f
                
                // Apply all factors and add small random noise
                val noise = (Math.random() * 0.05 - 0.025).toFloat()
                basePattern * leadFactor * abnormalityFactor + noise
            }
        }
    }
    
    /**
     * Helper endpoint to generate an intentional error - useful for debugging error handling
     */
    @GetMapping("/test/error")
    fun generateError(): ResponseEntity<Map<String, Any>> {
        try {
            throw RuntimeException("Intentional test error")
        } catch (e: Exception) {
            return ResponseEntity.internalServerError().body(mapOf(
                "success" to false,
                "error" to (e.message ?: "Unknown error"),
                "errorType" to e.javaClass.simpleName
            ))
        }
    }
}