package com.ecgapp.ecgapp.service

/**
 * Interface for classes that need to observe ECG connection events and data
 * This breaks the circular dependency between RealtimeEcgService and EcgTestController
 */
interface EcgDiagnosticObserver {
    /**
     * Called when a new connection is registered
     */
    fun registerConnection(userId: Long)
    
    /**
     * Called when a connection is closed or removed
     */
    fun unregisterConnection(userId: Long)
    
    /**
     * Called after data is successfully sent to a client
     */
    fun updateConnectionStats(userId: Long, packet: RealtimeEcgService.EcgDataPacket)
}