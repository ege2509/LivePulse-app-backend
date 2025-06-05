package com.ecgapp.ecgapp.config

import com.ecgapp.ecgapp.config.EcgWebSocketHandler
import com.ecgapp.ecgapp.service.ActiveEcgUserService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val activeEcgUserService: ActiveEcgUserService
) : WebSocketConfigurer {

    @Bean
    fun ecgWebSocketHandler(): EcgWebSocketHandler {
        return EcgWebSocketHandler(activeEcgUserService)
    }

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(ecgWebSocketHandler(), "/ws/ecg")
               .setAllowedOrigins("*")
    }
}