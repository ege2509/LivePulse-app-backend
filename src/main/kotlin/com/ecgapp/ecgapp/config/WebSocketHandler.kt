package com.ecgapp.ecgapp.config

import com.ecgapp.ecgapp.service.RealtimeEcgService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.ConcurrentHashMap

import org.slf4j.LoggerFactory

@Component
class EcgWebSocketHandler : TextWebSocketHandler() {
    // Store all active WebSocket sessions
    private val sessions = ConcurrentHashMap<String, WebSocketSession>()
    
    override fun afterConnectionEstablished(session: WebSocketSession) {
        // Store the session with its unique ID
        sessions[session.id] = session
        println("WebSocket client connected: ${session.id}")
    }
    
    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        // Remove the session when closed
        sessions.remove(session.id)
        println("WebSocket client disconnected: ${session.id}")
    }
    
    /**
     * Send a string message to all connected clients
     */
    fun sendToAllClients(message: String) {
        val textMessage = TextMessage(message)
        sendMessageToAll(textMessage)
    }
    
    /**
     * Send a binary message to all connected clients
     */
    fun sendToAllClients(message: BinaryMessage) {
        sendMessageToAll(message)
    }
    
    /**
     * Send message to a specific client by session ID
     */
    fun sendToClient(sessionId: String, message: String) {
        val session = sessions[sessionId] ?: return
        if (session.isOpen) {
            try {
                session.sendMessage(TextMessage(message))
            } catch (e: Exception) {
                println("Error sending message to client $sessionId: ${e.message}")
                // Remove the session if it's in a bad state
                sessions.remove(sessionId)
            }
        } else {
            // Remove closed sessions
            sessions.remove(sessionId)
        }
    }
    
    /**
     * Send message to a specific client by session ID
     */
    fun sendToClient(sessionId: String, message: BinaryMessage) {
        val session = sessions[sessionId] ?: return
        if (session.isOpen) {
            try {
                session.sendMessage(message)
            } catch (e: Exception) {
                println("Error sending binary message to client $sessionId: ${e.message}")
                // Remove the session if it's in a bad state
                sessions.remove(sessionId)
            }
        } else {
            // Remove closed sessions
            sessions.remove(sessionId)
        }
    }
    
    /**
     * Private helper method to send a message to all clients
     */
    private fun sendMessageToAll(message: Any) {
        // Create a copy of the sessions to avoid ConcurrentModificationException
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
                    // Remove the session if it's in a bad state
                    sessions.remove(sessionId)
                }
            } else {
                // Remove closed sessions
                sessions.remove(sessionId)
            }
        }
    }
}
