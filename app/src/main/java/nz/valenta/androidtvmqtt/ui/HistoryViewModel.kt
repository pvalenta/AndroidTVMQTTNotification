package nz.valenta.androidtvmqtt.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import nz.valenta.androidtvmqtt.data.MessageRepository
import nz.valenta.androidtvmqtt.model.ReceivedMessage
import kotlinx.coroutines.flow.StateFlow

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val messageRepo = MessageRepository.getInstance(application)
    val messages: StateFlow<List<ReceivedMessage>> = messageRepo.messages
}
