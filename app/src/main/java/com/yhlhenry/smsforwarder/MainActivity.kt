package com.yhlhenry.smsforwarder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.yhlhenry.smsforwarder.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshLogs()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.entries.filter { !it.value }.map { it.key }
        if (denied.any { it == Manifest.permission.RECEIVE_SMS }) {
            Toast.makeText(this, R.string.toast_sms_permission_required, Toast.LENGTH_LONG).show()
        }
        if (denied.any {
                it == Manifest.permission.READ_PHONE_STATE ||
                it == Manifest.permission.READ_PHONE_NUMBERS
            }) {
            Toast.makeText(this, R.string.toast_phone_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSettings()
        requestPermissions()

        binding.btnSave.setOnClickListener {
            saveSettings()
            Toast.makeText(this, R.string.toast_settings_saved, Toast.LENGTH_SHORT).show()
        }
        binding.btnTest.setOnClickListener { sendTestRequest() }
        binding.btnPickSms.setOnClickListener { pickSmsAndTest() }
        binding.btnClearLogs.setOnClickListener { clearLogs() }

        listOf(
            binding.chipReceiver  to "{receiver}",
            binding.chipSender    to "{sender}",
            binding.chipMessage   to "{message}",
            binding.chipDevice    to "{device}",
            binding.chipTimestamp to "{timestamp}"
        ).forEach { (chip, variable) ->
            chip.setOnClickListener { binding.etBodyTemplate.insertAtCursor(variable) }
        }

        refreshLogs()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.yhlhenry.smsforwarder.LOG_UPDATED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(logReceiver)
    }

    private fun requestPermissions() {
        val needed = listOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun loadSettings() {
        val prefs = prefs()
        binding.etUrl.setText(prefs.getString("api_url", ""))
        binding.etDeviceName.setText(prefs.getString("device_name", Build.MODEL))
        binding.etBodyTemplate.setText(
            prefs.getString("body_template", SmsReceiver.defaultTemplate())
        )
        when (prefs.getString("http_method", "POST")) {
            "GET" -> binding.rgMethod.check(R.id.rbGet)
            "PUT" -> binding.rgMethod.check(R.id.rbPut)
            else  -> binding.rgMethod.check(R.id.rbPost)
        }
    }

    private fun saveSettings() {
        prefs().edit()
            .putString("api_url", binding.etUrl.text.toString().trim())
            .putString("device_name", binding.etDeviceName.text.toString().trim().ifBlank { Build.MODEL })
            .putString("body_template", binding.etBodyTemplate.text.toString())
            .putString("http_method", selectedMethod())
            .apply()
    }

    private fun sendTestRequest() {
        val url = binding.etUrl.text.toString().trim()
        if (url.isBlank()) {
            Toast.makeText(this, R.string.toast_enter_url, Toast.LENGTH_SHORT).show()
            return
        }
        saveSettings()
        val method     = selectedMethod()
        val deviceName = binding.etDeviceName.text.toString().trim().ifBlank { Build.MODEL }
        val timestamp  = isoTimestamp()
        val receiver   = SmsReceiver.getOwnPhoneNumber(this).ifBlank { "(unknown)" }
        val testSender  = "+886912345678"
        val testMessage = "Test SMS from SMS Forwarder"
        val rendered = renderBody(receiver, testSender, testMessage, deviceName, timestamp)
        executeTestRequest(url, method, receiver, testSender, testMessage,
            rendered, deviceName, timestamp, binding.btnTest)
    }

    private fun refreshLogs() {
        val rawLogs = prefs().getString("logs", "").orEmpty()
        val logFile = SmsReceiver.logFile(this)

        val header = if (logFile.exists())
            "完整記錄：${logFile.absolutePath} (${logFile.length() / 1024} KB)\n\n"
        else ""

        val body = if (rawLogs.isBlank()) {
            getString(R.string.log_empty)
        } else {
            rawLogs.lines()
                .filter { it.isNotBlank() }
                .reversed()
                .joinToString("\n\n") { formatLogEntry(it) }
        }

        binding.tvLogs.text = header + body
        binding.scrollView.post { binding.scrollView.fullScroll(android.view.View.FOCUS_UP) }
    }

    /** Formats a stored log line into a readable multi-line block.
     *  Storage format: MM-dd HH:mm:ss|sender|receiver|status
     *  Falls back gracefully for legacy single-line entries. */
    private fun formatLogEntry(line: String): String {
        val parts = line.split("|")
        if (parts.size < 4) return line  // legacy format
        val (time, sender, receiver, status) = parts
        val icon = when {
            status.startsWith("HTTP 2") -> "✓"
            status.startsWith("ERROR")  -> "✗"
            else -> "·"
        }
        return "$time\n  sender    $sender\n  receiver  $receiver\n  $icon $status"
    }

    private fun clearLogs() {
        prefs().edit().remove("logs").apply()
        SmsReceiver.logFile(this).delete()
        refreshLogs()
    }

    private fun prefs() = getSharedPreferences(SmsReceiver.PREFS_NAME, Context.MODE_PRIVATE)

    private fun selectedMethod() = when (binding.rgMethod.checkedRadioButtonId) {
        R.id.rbGet -> "GET"
        R.id.rbPut -> "PUT"
        else       -> "POST"
    }

    private fun isoTimestamp() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date())

    // ── Pick SMS from inbox ───────────────────────────────────────────────────

    // displayName: contact name (for list display only); address: raw sender number used in payload
    private data class SmsItem(val address: String, val displayName: String, val body: String, val date: Long)

    private fun pickSmsAndTest() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.toast_read_sms_denied, Toast.LENGTH_SHORT).show()
            return
        }

        val smsList = readRecentSms(limit = 30)
        if (smsList.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_sms_found, Toast.LENGTH_SHORT).show()
            return
        }

        val labels = smsList.map { sms ->
            val header = if (sms.displayName != sms.address) "${sms.displayName}\n${sms.address}" else sms.address
            "$header\n${sms.body.take(60).replace("\n", " ")}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_pick_sms_title)
            .setItems(labels) { _, index -> showPayloadPreview(smsList[index]) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showPayloadPreview(sms: SmsItem) {
        val url        = binding.etUrl.text.toString().trim()
        val method     = selectedMethod()
        val deviceName = binding.etDeviceName.text.toString().trim().ifBlank { Build.MODEL }
        val timestamp  = isoTimestamp()
        val receiver   = SmsReceiver.getOwnPhoneNumber(this).ifBlank { "(unknown)" }
        // {sender} = sender's raw phone number; contact name is for display only
        val rendered   = renderBody(receiver, sms.address, sms.body, deviceName, timestamp)

        val preview = buildString {
            append("── 變數值 ──────────────────\n")
            append("{receiver}  $receiver\n")
            append("{sender}    ${sms.address}\n")
            if (sms.displayName != sms.address) append("  (聯絡人: ${sms.displayName})\n")
            append("{message}   ${sms.body.take(60)}${if (sms.body.length > 60) "…" else ""}\n")
            append("{device}    $deviceName\n")
            append("{timestamp} $timestamp\n")
            if (method != "GET") {
                append("\n── $method body ─────────────\n")
                append(rendered)
            }
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_payload_preview_title, method))
            .setMessage(preview)
            .setPositiveButton(R.string.btn_send) { _, _ ->
                executeTestRequest(url, method, receiver, sms.address, sms.body,
                    rendered, deviceName, timestamp, binding.btnPickSms)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun readRecentSms(limit: Int): List<SmsItem> {
        val result = mutableListOf<SmsItem>()
        val cursor = contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null,
            "${Telephony.Sms.DATE} DESC LIMIT $limit"
        ) ?: return result

        cursor.use {
            val colAddress = it.getColumnIndex(Telephony.Sms.ADDRESS)
            val colBody    = it.getColumnIndex(Telephony.Sms.BODY)
            val colDate    = it.getColumnIndex(Telephony.Sms.DATE)
            while (it.moveToNext()) {
                val address     = it.getString(colAddress).orEmpty()
                val body        = it.getString(colBody).orEmpty()
                val date        = it.getLong(colDate)
                val displayName = if (ContextCompat.checkSelfPermission(
                        this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
                    lookupContactName(address) else address
                result.add(SmsItem(address, displayName, body, date))
            }
        }
        return result
    }

    private fun lookupContactName(phoneNumber: String): String {
        return try {
            val uri = android.net.Uri.withAppendedPath(
                android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(phoneNumber)
            )
            contentResolver.query(
                uri,
                arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: phoneNumber
        } catch (e: Exception) {
            phoneNumber
        }
    }

    /**
     * Renders the body template with actual values.
     * @param receiver  {receiver} — this device's phone number
     * @param sender    {sender}   — the sender's phone number
     */
    private fun renderBody(
        receiver: String,
        sender: String,
        message: String,
        deviceName: String,
        timestamp: String
    ) = binding.etBodyTemplate.text.toString()
        .replace("{receiver}",  receiver)
        .replace("{sender}",    sender)
        .replace("{message}",   message)
        .replace("{device}",    deviceName)
        .replace("{timestamp}", timestamp)

    private fun executeTestRequest(
        url: String, method: String,
        receiver: String, sender: String, message: String,
        renderedBody: String, deviceName: String, timestamp: String,
        disableButton: android.widget.Button
    ) {
        if (url.isBlank()) {
            Toast.makeText(this, R.string.toast_enter_url, Toast.LENGTH_SHORT).show()
            return
        }
        disableButton.isEnabled = false
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching {
                val client = OkHttpClient()
                val json   = "application/json".toMediaType()
                val request = when (method) {
                    "GET" -> Request.Builder()
                        .url("$url?receiver=${receiver.urlEncode()}&sender=${sender.urlEncode()}&message=${message.urlEncode()}&device=${deviceName.urlEncode()}&timestamp=${timestamp.urlEncode()}")
                        .get().build()
                    "PUT" -> Request.Builder().url(url).put(renderedBody.toRequestBody(json)).build()
                    else  -> Request.Builder().url(url).post(renderedBody.toRequestBody(json)).build()
                }
                val response     = client.newCall(request).execute()
                val code         = response.code
                val responseBody = response.body?.string()?.take(300).orEmpty()
                response.close()
                if (responseBody.isNotBlank()) "HTTP $code: $responseBody" else "HTTP $code"
            }.getOrElse { "Error: ${it.message}" }

            runOnUiThread {
                Toast.makeText(this@MainActivity, result, Toast.LENGTH_SHORT).show()
                disableButton.isEnabled = true
            }
        }
    }

    private fun String.urlEncode() = URLEncoder.encode(this, "UTF-8")

    private fun android.widget.EditText.insertAtCursor(text: String) {
        val start = selectionStart.coerceAtLeast(0)
        val end   = selectionEnd.coerceAtLeast(0)
        editableText.replace(minOf(start, end), maxOf(start, end), text)
    }
}
