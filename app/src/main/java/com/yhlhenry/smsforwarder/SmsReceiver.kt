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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        const val PREFS_NAME = "sms_forwarder"

        // Variable reference:
        //   {receiver}  — this device's phone number (the SIM that received the SMS)
        //   {sender}    — the phone number that sent the SMS
        //   {message}   — the SMS body
        //   {device}    — device model name (user-configurable)
        //   {timestamp} — UTC ISO-8601 timestamp
        fun defaultTemplate(): String = "{\n" +
            "  \"receiver\": \"{receiver}\",\n" +
            "  \"sender\": \"{sender}\",\n" +
            "  \"message\": \"{message}\",\n" +
            "  \"device\": \"{device}\",\n" +
            "  \"timestamp\": \"{timestamp}\"\n" +
            "}"

        fun logFile(context: Context) = java.io.File(context.getExternalFilesDir(null), "sms_log.txt")

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
        val method = prefs.getString("http_method", "POST").orEmpty().ifBlank { "POST" }
        val bodyTemplate = prefs.getString("body_template", defaultTemplate()).orEmpty()
            .ifBlank { defaultTemplate() }
        val deviceName = prefs.getString("device_name", Build.MODEL).orEmpty()
            .ifBlank { Build.MODEL }

        if (url.isBlank()) {
            Log.w(TAG, "API URL not configured, skipping forward")
            return
        }

        val timestamp = isoTimestamp()
        val receiver = getOwnPhoneNumber(context)
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                for ((sender, msgBuilder) in assembled) {
                    val message = msgBuilder.toString()
                    forwardSms(context, url, method, bodyTemplate,
                        receiver, sender, message, deviceName, timestamp)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun forwardSms(
        context: Context,
        url: String,
        method: String,
        bodyTemplate: String,
        receiver: String,    // {receiver} — this device's number
        sender: String,      // {sender}   — who sent the SMS
        message: String,     // {message}  — SMS body
        deviceName: String,
        timestamp: String
    ) {
        val body = bodyTemplate
            .replace("{receiver}",  receiver.jsonEscape())
            .replace("{sender}",    sender.jsonEscape())
            .replace("{message}",   message.jsonEscape())
            .replace("{device}",    deviceName.jsonEscape())
            .replace("{timestamp}", timestamp.jsonEscape())

        val status = try {
            val client = OkHttpClient()
            val json = "application/json".toMediaType()
            val request = when (method.uppercase()) {
                "GET" -> Request.Builder()
                    .url("$url?receiver=${receiver.urlEncode()}&sender=${sender.urlEncode()}&message=${message.urlEncode()}&device=${deviceName.urlEncode()}&timestamp=${timestamp.urlEncode()}")
                    .get().build()
                "PUT" -> Request.Builder().url(url).put(body.toRequestBody(json)).build()
                else  -> Request.Builder().url(url).post(body.toRequestBody(json)).build()
            }
            val response = client.newCall(request).execute()
            val code = response.code
            val responseBody = response.body?.string()?.take(500).orEmpty()
            response.close()
            Log.i(TAG, "Forwarded SMS  sender=$sender  receiver=$receiver  HTTP $code")
            if (responseBody.isNotBlank() && responseBody != "ok")
                "HTTP $code ($responseBody)"
            else
                "HTTP $code"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to forward SMS from $sender", e)
            "ERROR: ${e.message}"
        }

        appendLog(context, sender, receiver, status)
    }

    /** Stores one log entry as a pipe-separated line: localTime|sender|receiver|status */
    private fun appendLog(context: Context, sender: String, receiver: String, status: String) {
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

    private fun isoTimestamp() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date())

    private fun localTimestamp() = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        .format(Date())

    private fun String.urlEncode() = URLEncoder.encode(this, "UTF-8")

    private fun String.jsonEscape(): String = JSONObject.quote(this).let { it.substring(1, it.length - 1) }
}
