
package com.ecgapp.ecgapp.service

import org.springframework.stereotype.Service

@Service
class ActiveEcgUserService {
    @Volatile
    private var currentUserId: Long? = null
    
    fun setActiveUser(userId: Long) {
        currentUserId = userId
        println("Active ECG user set to: $userId")
    }
    
    fun getCurrentUserId(): Long? {
    println(">>>>>> getCurrentUserId() called, returning: $currentUserId <<<<<<")
    return currentUserId
}

    
    fun clearActiveUser() {
        currentUserId = null
        println("Active ECG user cleared")
    }
}