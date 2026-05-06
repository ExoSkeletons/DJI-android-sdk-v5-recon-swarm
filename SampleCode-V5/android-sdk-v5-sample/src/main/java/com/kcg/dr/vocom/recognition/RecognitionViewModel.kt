package com.kcg.dr.vocom.recognition

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class RecognitionViewModel : ViewModel() {
    private val memory = RecognitionMemory(30.0) // 30 seconds memory
    
    val lastSeenObject = MutableLiveData<RecognitionMemory.RecognitionSample.ObstacleInfo?>()
    val objectsInMemory = MutableLiveData<List<RecognitionMemory.RecognitionSample.ObstacleInfo>>()

    init {
        memory.onSeen = { _ ->
            objectsInMemory.postValue(memory.memory.toList())
            lastSeenObject.postValue(memory.lastSeen())
        }
    }

    fun processSample(sample: RecognitionMemory.RecognitionSample) {
        memory.see(sample)
    }

    fun clear() {
        memory.clear()
        objectsInMemory.postValue(emptyList())
        lastSeenObject.postValue(null)
    }
}
