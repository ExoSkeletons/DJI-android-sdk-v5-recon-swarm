package com.kcg.dr.yerut

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData

class OscVm(application: Application) : AndroidViewModel(
    application
) {
    val ampH: MutableLiveData<Double> = MutableLiveData(0.0)
    val freqH: MutableLiveData<Double> = MutableLiveData(0.0)
}