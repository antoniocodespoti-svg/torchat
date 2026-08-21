package com.p2p.torchat.ui.viewmodel

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.p2p.torchat.data.ChatRepository
import com.p2p.torchat.model.Message
import com.p2p.torchat.model.Peer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(private val repository: ChatRepository) : ViewModel() {
    val messagesMap = mutableStateMapOf<String, MutableList<Message>>()
    val peersList = mutableStateListOf<Peer>()

    private val _isHandshakeLoading = mutableStateMapOf<String, Boolean>()
    val handshakeLoading: Map<String, Boolean> get() = _isHandshakeLoading

    fun sendMessage(peer: Peer, content: String) {
        viewModelScope.launch {
            val result = repository.sendMessage(peer, content)
            result.onSuccess { msg ->
                messagesMap.getOrPut(peer.onionAddress) { mutableStateListOf() }.add(msg)
            }
        }
    }

    fun addPeer(peer: Peer) {
        if (peersList.none { it.onionAddress == peer.onionAddress }) {
            peersList.add(peer)
        }
    }

    fun setHandshakeLoading(onion: String, isLoading: Boolean) {
        _isHandshakeLoading[onion] = isLoading
    }
}
