package com.ecgapp.ecgapp.config

import com.ecgapp.ecgapp.service.EcgMessagePublisher
import com.ecgapp.ecgapp.service.RealtimeEcgService
import com.ecgapp.ecgapp.service.ActiveEcgUserService
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.ConcurrentHashMap

@Component
class EcgWebSocketHandler(
    private val activeEcgUserService: ActiveEcgUserService
) : TextWebSocketHandler(), EcgMessagePublisher {
    private val sessions = ConcurrentHashMap<String, WebSocketSession>()
    private val userIdToSessionId = ConcurrentHashMap<Long, String>()
    
    override fun afterConnectionEstablished(session: WebSocketSession) {
        // Store the session with its unique ID
        sessions[session.id] = session
        val userId = extractUserId(session)
        if (userId != null) {
            // Map user ID to session ID
            userIdToSessionId[userId] = session.id
            
            // Set this user as the active ECG user
            activeEcgUserService.setActiveUser(userId)
            
            println("WebSocket client connected: ${session.id}, User ID: $userId")
        } else {
            println("WebSocket client connected without user ID: ${session.id}")
        }
    }
    
    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        // Remove the session when closed
        sessions.remove(session.id)
        
        // Find and remove the user ID mapping
        val userId = extractUserId(session)
        if (userId != null) {
            userIdToSessionId.remove(userId)
            
            // Clear the active user if this was the active user
            if (activeEcgUserService.getCurrentUserId() == userId) {
                activeEcgUserService.clearActiveUser()
            }
        }
        
        println("WebSocket client disconnected: ${session.id}")
    }

    private fun extractUserId(session: WebSocketSession): Long? {
        // Extract from URL query parameters
        val parameters = session.uri?.query?.split("&")?.associate { 
            val parts = it.split("=")
            if (parts.size == 2) parts[0] to parts[1] else it to ""
        }
        
        val userIdStr = parameters?.get("userId")
        return userIdStr?.toLongOrNull()?.takeIf { it > 0 } // Return null if userId is -1 or invalid
    }
    
    // EcgMessagePublisher implementation
    override fun publishToUser(userId: Long, message: String) {
        val sessionId = userIdToSessionId[userId] ?: return
        sendToClient(sessionId, message)
    }
    
    override fun publishToAll(message: String) {
        sendToAllClients(message)
    }
    
    override fun publishBinaryToUser(userId: Long, data: ByteArray) {
        val sessionId = userIdToSessionId[userId] ?: return
        sendToClient(sessionId, BinaryMessage(data))
    }
    
    override fun publishBinaryToAll(data: ByteArray) {
        sendToAllClients(BinaryMessage(data))
    }
    
    
    private fun sendToAllClients(message: String) {
        val textMessage = TextMessage(message)
        sendMessageToAll(textMessage)
    }
    
    private fun sendToAllClients(message: BinaryMessage) {
        sendMessageToAll(message)
    }
    
    private fun sendToClient(sessionId: String, message: String) {
        val session = sessions[sessionId] ?: return
        if (session.isOpen) {
            try {
                session.sendMessage(TextMessage(message))
            } catch (e: Exception) {
                println("Error sending message to client $sessionId: ${e.message}")
                sessions.remove(sessionId)
            }
        } else {
            sessions.remove(sessionId)
        }
    }
    
    private fun sendToClient(sessionId: String, message: BinaryMessage) {
        val session = sessions[sessionId] ?: return
        if (session.isOpen) {
            try {
                session.sendMessage(message)
            } catch (e: Exception) {
                println("Error sending binary message to client $sessionId: ${e.message}")
                sessions.remove(sessionId)
            }
        } else {
            sessions.remove(sessionId)
        }
    }

    fun publishEcgData(userId: Long, packet: RealtimeEcgService.EcgDataPacket) {
        // Convert to JSON
        val json = packet.toFrontendJson()
        // Send to client
        publishToUser(userId, json)  
    }
    
    private fun sendMessageToAll(message: Any) {
        val sessionsCopy = HashMap(sessions)
        
        for ((sessionId, session) in sessionsCopy) {
            if (session.isOpen) {
                try {
                    when (message) {
                        is TextMessage -> session.sendMessage(message)
                        is BinaryMessage -> session.sendMessage(message)
                        is String -> session.sendMessage(TextMessage(message))
                    }
                } catch (e: Exception) {
                    println("Error sending message to client $sessionId: ${e.message}")
                    sessions.remove(sessionId)
                }
            } else {
                sessions.remove(sessionId)
            }
        }
    }
}