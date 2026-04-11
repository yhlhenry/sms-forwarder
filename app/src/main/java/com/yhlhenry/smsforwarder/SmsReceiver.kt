package com.yhlhenry.smsforwarder

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        const val PREFS_NAME = "sms_forwarder"

        fun logFile(context: Context) = java.io.File(context.getExternalFilesDir(null), "sms_log.txt")

        /** Builds the Slack Workflow webhook JSON payload. */
        fun buildPayload(
            sender: String,
            message: String,
            receiver: String,
            deviceName: String,
            timestamp: String
        ): String = JSONObject().apply {
            put("sender",    sender)
            put("message",   message)
            put("receiver",  receiver)
            put("device",    deviceName)
            put("timestamp", timestamp)
        }.toString()

        fun appendLog(context: Context, sender: String, receiver: String, status: String) {
            val entry = "${localTimestamp()}|$sender|$receiver|$status"
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lines = prefs.getString("logs", "").orEmpty()
                .lines().filter { it.isNotBlank() }.takeLast(99).toMutableList()
            lines.add(entry)
            prefs.edit().putString("logs", lines.joinToString("\n")).apply()
            try {
                logFile(context).appendText("$entry\n")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write log to file", e)
            }
            context.sendBroadcast(Intent("com.yhlhenry.smsforwarder.LOG_UPDATED"))
        }

        fun localTimestamp() = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        /** Returns this device's own phone number, or empty string if unavailable. */
        @SuppressLint("HardwareIds", "MissingPermission")
        fun getOwnPhoneNumber(context: Context): String {
            return try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val number = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                        as android.telephony.SubscriptionManager
                    sm.activeSubscriptionInfoList?.firstOrNull()?.number
                } else {
                    @Suppress("DEPRECATION")
                    tm.line1Number
                }
                number?.takeIf { it.isNotBlank() } ?: ""
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get own phone number", e)
                ""
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        // Assemble multipart SMS by sender
        val assembled = linkedMapOf<String, StringBuilder>()
        for (sms in messages) {
            val sender = sms.originatingAddress ?: "Unknown"
            assembled.getOrPut(sender) { StringBuilder() }.append(sms.messageBody)
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val url = prefs.getString("api_url", "").orEmpty()
        val deviceName = prefs.getString("device_name", Build.MODEL).orEmpty().ifBlank { Build.MODEL }
        val myNumber = prefs.getString("my_number", "").orEmpty()

        if (url.isBlank()) {
            Log.w(TAG, "Slack webhook URL not configured, skipping forward")
            return
        }

        val timestamp = isoTimestamp()
        val receiver = myNumber.ifBlank { getOwnPhoneNumber(context) }
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                for ((sender, msgBuilder) in assembled) {
                    val message = msgBuilder.toString()
                    forwardToSlack(context, url, sender, message, receiver, deviceName, timestamp)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun forwardToSlack(
        context: Context,
        url: String,
        sender: String,
        message: String,
        receiver: String,
        deviceName: String,
        timestamp: String
    ) {
        val payload = buildPayload(sender, message, receiver, deviceName, timestamp)
        val status = try {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url(url)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            val code = response.code
            val responseBody = response.body?.string()?.take(500).orEmpty()
            response.close()
            Log.i(TAG, "Forwarded SMS to Slack  sender=$sender  HTTP $code")
            if (responseBody.isNotBlank() && responseBody != "ok") "HTTP $code ($responseBody)"
            else "HTTP $code"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to forward SMS from $sender", e)
            "ERROR: ${e.message}"
        }

        appendLog(context, sender, receiver, status)
    }

    private fun isoTimestamp() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date())
}
