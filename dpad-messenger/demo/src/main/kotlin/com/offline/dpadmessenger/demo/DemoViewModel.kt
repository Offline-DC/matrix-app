package com.offline.dpadmessenger.demo

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offline.dpadmessenger.data.InMemoryMessageRepository
import com.offline.dpadmessenger.data.MessageRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Bootstraps the mock repo and drives a background "phantom traffic" coroutine
 * that drops a new incoming message every ~12s so you can see the unread badge
 * tick and the bubble animate in without touching anything.
 */
class DemoViewModel(appContext: Context) : ViewModel() {

    private val _repository = MutableStateFlow<MessageRepository?>(null)
    val repository = _repository.asStateFlow()

    init {
        _repository.value = InMemoryMessageRepository.fromAssets(appContext)
    }

    fun startSimulatedTraffic() {
        viewModelScope.launch {
            val repo = _repository.value ?: return@launch
            val canned = listOf(
                "r1" to ("alice" to "Hey, are you free tonight?"),
                "r1" to ("alice" to "Want to grab dinner?"),
                "r2" to ("bob" to "Anyone got the doc?"),
                "r2" to ("carol" to "Here's the latest version 🎉"),
                "r3" to ("dave" to "Order's out for delivery."),
            )
            var index = 0
            while (true) {
                delay(12_000)
                val (room, senderAndBody) = canned[index % canned.size]
                val (sender, body) = senderAndBody
                runCatching { repo.simulateIncoming(room, sender, body) }
                index++
            }
        }
    }
}
