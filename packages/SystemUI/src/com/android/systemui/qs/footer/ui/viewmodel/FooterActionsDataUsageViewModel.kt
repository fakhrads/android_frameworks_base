/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.footer.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.telephony.SubscriptionManager
import android.text.format.Formatter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.settingslib.net.DataUsageController
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.connectivity.NetworkController
import com.android.systemui.statusbar.connectivity.SignalCallback
import com.android.systemui.statusbar.connectivity.WifiIndicators
import com.android.systemui.tuner.TunerService
import com.android.systemui.util.settings.GlobalSettings

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject


/**
 * ViewModel for data usage display in QS footer
 */
class FooterActionsDataUsageViewModel @Inject constructor(
    private val context: Context,
    private val dataController: DataUsageController,
    private val subManager: SubscriptionManager,
    private val wifiManager: WifiManager?,
    private val networkController: NetworkController,
    private val tunerService: TunerService,
    private val globalSettings: GlobalSettings,
    private val activityStarter: ActivityStarter,
    private val userTracker: UserTracker,
    @Background backgroundDispatcher: CoroutineDispatcher,
) : ViewModel() {

    // DataUsageController keeps the subscription between calls, so queries run one at a time.
    private val queryDispatcher = backgroundDispatcher.limitedParallelism(1)
    private var updateJob: Job? = null
    
    private val _dataUsageText = MutableStateFlow<String?>(null)
    val dataUsageText: StateFlow<String?> = _dataUsageText.asStateFlow()
    
    private val _isVisible = MutableStateFlow(false)
    val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()
    
    private val _showSuffix = MutableStateFlow(false)
    val showSuffix: StateFlow<Boolean> = _showSuffix.asStateFlow()
    
    private var hasNoSims = false
    private var isWifiConnected = false
    private var wifiSsid: String? = null
    private var subId = 0
    private var currentDataSubId = 0
    private var hideDataUsage = false
    private var cycleType = 0 // 0 = Daily, 1 = Weekly


    private val tunerCallback = object : TunerService.Tunable {
        override fun onTuningChanged(key: String?, newValue: String?) {
            when (key) {
                "qs_show_data_usage" -> {
                    hideDataUsage = !TunerService.parseIntegerSwitch(newValue, true)
                    updateDataUsage()
                }
                "qs_data_usage_cycle_type" -> {
                    cycleType = TunerService.parseInteger(newValue, 0)
                    updateDataUsage()
                }

            }
        }
    }
    
    private val dataSwitchObserver = object : android.database.ContentObserver(android.os.Handler()) {
        override fun onChange(selfChange: Boolean) {
            onDefaultDataSimChanged()
        }
    }
    
    private val signalCallback = object : SignalCallback {
        override fun setNoSims(show: Boolean, simDetected: Boolean) {
            hasNoSims = show
            updateDataUsage()
        }
        
        override fun setWifiIndicators(wifiIndicators: WifiIndicators) {
            isWifiConnected = wifiIndicators.enabled && wifiIndicators.qsIcon?.visible == true
            if (isWifiConnected) {
                onWifiStatusUpdated()
            }
            updateDataUsage()
        }
    }
    
    init {
        // Listen for tuner changes
        tunerService.addTunable(tunerCallback, "qs_show_data_usage", "qs_data_usage_cycle_type")
        
        // Listen for data subscription changes
        globalSettings.registerContentObserverSync(
            "multi_sim_data_call_subscription",
            dataSwitchObserver
        )
        
        // Listen for network changes
        networkController.addCallback(signalCallback)
        
        // Set initial values
        onDefaultDataSimChanged()
        
        // Initialize settings from tuner
        hideDataUsage = !TunerService.parseIntegerSwitch(tunerService.getValue("qs_show_data_usage"), true)
        cycleType = TunerService.parseInteger(tunerService.getValue("qs_data_usage_cycle_type"), 0)

        
        // Set default values if they don't exist
        if (tunerService.getValue("qs_data_usage_cycle_type") == null) {
            tunerService.setValue("qs_data_usage_cycle_type", "0")
        }

        
        // Update data usage with initial settings
        updateDataUsage()
    }

    private fun onWifiStatusUpdated() {
        // The SSID is read with the usage, off the main thread.
        updateDataUsage()
    }

    private fun onDefaultDataSimChanged() {
        currentDataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
        if (currentDataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            subId = currentDataSubId
        }
        updateDataUsage()
    }

    /**
     * Queries the usage in the background and publishes it. The query reads network stats and can
     * take tens of milliseconds, and signal callbacks ask for it in bursts (several per shade
     * opening, and on every Wi-Fi level change), so a request replaces one that has not started yet.
     */
    private fun updateDataUsage() {
        updateJob?.cancel()
        if (hideDataUsage) {
            _isVisible.value = false
            return
        }
        val wifiConnected = isWifiConnected
        val noSims = hasNoSims
        val sub = subId
        val weekly = cycleType == 1
        val showSuffix = _showSuffix.value
        updateJob =
            viewModelScope.launch {
                delay(UPDATE_DEBOUNCE_MS)
                val usageText =
                    withContext(queryDispatcher) {
                        queryUsageText(wifiConnected, noSims, sub, weekly, showSuffix)
                    }
                if (usageText == null) {
                    _isVisible.value = false
                } else {
                    _dataUsageText.value = usageText
                    _isVisible.value = true
                }
            }
    }

    /** Runs on [queryDispatcher]. */
    private fun queryUsageText(
        wifiConnected: Boolean,
        noSims: Boolean,
        sub: Int,
        weekly: Boolean,
        showSuffix: Boolean,
    ): String? {
        val info =
            when {
                wifiConnected -> {
                    if (weekly) {
                        dataController.getWifiDataUsageInfo(true)
                            ?: dataController.getWifiDataUsageInfo(false)
                    } else {
                        dataController.getWifiDailyDataUsageInfo(true)
                            ?: dataController.getWifiDailyDataUsageInfo(false)
                    }
                }
                !noSims && sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID -> {
                    dataController.setSubscriptionId(sub)
                    if (weekly) {
                        dataController.getDataUsageInfo()
                    } else {
                        dataController.getDailyDataUsageInfo()
                    }
                }
                else -> null
            } ?: return null

        if (wifiConnected) {
            wifiSsid = wifiManager?.connectionInfo?.takeIf { it.networkId != -1 }?.ssid
        }
        val suffix =
            if (!showSuffix) {
                ""
            } else
                when {
                    wifiConnected ->
                        wifiSsid?.replace("\"", "")
                            ?: context.getString(R.string.usage_wifi_default_suffix)
                    !noSims ->
                        subManager.getActiveSubscriptionInfo(sub)?.displayName?.toString()
                            ?: context.getString(R.string.usage_data_default_suffix)
                    else -> context.getString(R.string.usage_data_default_suffix)
                }
        return formatDataUsage(info.usageLevel, suffix, weekly, showSuffix)
    }

    private fun formatDataUsage(
        byteValue: Long,
        suffix: String,
        weekly: Boolean,
        showSuffix: Boolean,
    ): String {
        val usage = StringBuilder(Formatter.formatFileSize(context, byteValue, Formatter.FLAG_IEC_UNITS))
            .append(" ")
            .append(if (weekly) {
                context.getString(R.string.usage_data_weekly)
            } else {
                context.getString(R.string.usage_data)
            })
        
        if (showSuffix) {
            usage.append(" (")
                .append(suffix)
                .append(")")
        }
        
        return usage.toString()
    }

    fun onDataUsageClick() {
        if (!_showSuffix.value) {
            _showSuffix.value = true
        } else if (subManager.activeSubscriptionInfoCount > 1) {
            // Switch between SIM slots - find next available subscription
            val activeSubs = subManager.activeSubscriptionInfoList
            if (activeSubs != null && activeSubs.size > 1) {
                val currentIndex = activeSubs.indexOfFirst { it.subscriptionId == subId }
                val nextIndex = if (currentIndex >= 0) (currentIndex + 1) % activeSubs.size else 0
                subId = activeSubs[nextIndex].subscriptionId
            }
        } else {
            // Hide suffix if only one SIM
            _showSuffix.value = false
        }
        updateDataUsage()
    }

    fun onDataUsageLongClick() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            setClassName("com.android.settings", "com.android.settings.Settings\$DataUsageSummaryActivity")
        }
        activityStarter.startActivity(intent, true)
    }

    override fun onCleared() {
        super.onCleared()
        updateJob?.cancel()
        networkController.removeCallback(signalCallback)
        tunerService.removeTunable(tunerCallback)
        globalSettings.unregisterContentObserverSync(dataSwitchObserver)
    }

    private companion object {
        const val UPDATE_DEBOUNCE_MS = 200L
    }
}
