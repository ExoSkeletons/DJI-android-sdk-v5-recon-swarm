package com.kcg.dr.vocom.demo

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import kotlin.time.Duration.Companion.seconds

class DemoViewModel : ViewModel() {
    
    data class DemoFlightConfig(
        val humanHeight: Double = 2.0,
        val cruiseHeight: Double,
        val scanHeightHigh: Double,
        val scanRadiusHigh: Double,
        val scanHeightLow: Double,
        val scanRadiusLow: Double,
        val ascendVelocity: Double = 0.5,
        val descendVelocity: Double = 0.5,
        val scanVelocity: Double,
        val maxVelocity: Double,
        val accelerationDist: Double = 2.0,
        val decelerationDist: Double = 2.0,
        val flyToTolerance: Double = 1.0,
        val followDistance: Double,
        val followVelocity: Double = maxVelocity,
        val watch12Time: kotlin.time.Duration = 30.seconds,
        val watch6Time: kotlin.time.Duration = 3.seconds,
        val circleError: Double = 0.0,
    )

    val emptyLotConfig = DemoFlightConfig(
        humanHeight = 3.0,
        cruiseHeight = 30.0,
        followDistance = 14.0,
        scanHeightHigh = 40.0,
        scanRadiusHigh = 12.0,
        scanHeightLow = 14.0,
        scanRadiusLow = 8.0,
        ascendVelocity = 4.0,
        descendVelocity = 2.0,
        scanVelocity = 4.0,
        maxVelocity = 8.0,
        accelerationDist = 5.0,
        decelerationDist = 15.0,
        followVelocity = 3.0,
        circleError = -0.15,
    )

    private val _currentConfig = MutableLiveData(emptyLotConfig)
    val currentConfig: LiveData<DemoFlightConfig> = _currentConfig

    private val _demoTextIndex = MutableLiveData(0)
    val demoTextIndex: LiveData<Int> = _demoTextIndex

    val demoTexts = listOf(
        "בשעה 12, במרחק 200 מטר, הולך רגל , חולצה צהובה מתקדם לכיוונך",
        "בשעה 12 , במרחק 150 מטר, צומת דרכים.",
        "ממצאי סריקה: בשעה 12, במרחק 100 מטר, צומת דרכים. ",
        "בשעה 1, במרחק 100 מטר שיחים, חשוד מאחורי שיחים. ",
        "בשעה 3, 250 מטר לאחר הצומת, מגרש חנייה. ",
        "אין ממצאים נוספים",
        "ממצאי חקירה: הולך רגל בחולצה צהובה, ללא חפצים חשודים",
        "ממצאי סריקה: בשעה 3 במרחק 50 מטר, הולך רגל בחולצה אדומה. ",
        "הולך רגל בשעה 2 מהכניסה לחניה. ",
        "אין ממצאים נוספים",
        "ממצאי חקירה: בשעה 11, 20 מטר ממך, חשוד בחולצה אדומה עומד בקרבת הכניסה לחנייה ומתצפת. ",
        "בשעה 2, חשוד בחולצה אדומה נע לכיוון גבעת הדגל ",
        "אין ממצאים נוספים",
        "ממצאי סריקה: בשעה 3 במרחק 50 מטר, הולך רגל בחולצה אדומה. ",
        "הולך רגל בשעה 2 מהכניסה לחניה. ",
        "אין ממצאים נוספים",
        "אותר: בשעה 11 במרחק 10 מטרים כניסה לשביל עוקף",
        "ממצאי חקירה: בשעה 2, במרחק 50 מטר, דגל אדום. ",
        "במרחק 50 מטר, שני חשודים בחולצות אדומות, סמוך לדגל. ",
        "שני חשודים, חולצות אדומות, תנועה לשעה 3, 150 מטר. "
    )

    fun nextText(wrap: Boolean = true): String? {
        val nextIndex = (_demoTextIndex.value ?: 0) + 1
        return if (nextIndex < demoTexts.size) {
            _demoTextIndex.postValue(nextIndex)
            demoTexts[nextIndex]
        } else if (wrap) {
            _demoTextIndex.postValue(0)
            demoTexts[0]
        } else {
            null
        }
    }

    fun prevText(wrap: Boolean = true): String? {
        val prevIndex = (_demoTextIndex.value ?: 0) - 1
        return if (prevIndex >= 0) {
            _demoTextIndex.postValue(prevIndex)
            demoTexts[prevIndex]
        } else if (wrap) {
            val lastIndex = demoTexts.size - 1
            _demoTextIndex.postValue(lastIndex)
            demoTexts[lastIndex]
        } else {
            null
        }
    }

    fun getCurrentText(): String? {
        val i = _demoTextIndex.value ?: 0
        return demoTexts.getOrNull(i)
    }
}
