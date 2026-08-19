package com.tarikul.cs15ip

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * CODE LAB — systematic IR discovery for the Sony ICF-CS15iP.
 *
 * Sweeps the Sony SIRC space, keeps a rolling buffer of the last codes sent,
 * and logs everything you MARK (plus manual tests) to a CSV you can save to
 * your phone's Downloads or share (e.g. back into a chat) so the working
 * codes can be turned into real remote buttons.
 */
class LabActivity : AppCompatActivity() {

    private lateinit var ir: SonyIrBlaster
    private var job: Job? = null

    private data class Sent(val bits: Int, val addr: Int, val ext: Int, val cmd: Int)
    private val recent = ArrayDeque<Sent>()          // last codes sent (reaction lag buffer)
    private val logLines = StringBuilder("timestamp,event,bits,address,extended,command,note\n")
    private var sentCount = 0

    private val ts get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    // ---- Microphone auto-detect: send a code, compare loudness before/after ----
    private var audio: AudioRecord? = null
    private val sampleRate = 44100

    private fun micReady(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 7)
            return false
        }
        if (audio == null) {
            val buf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(sampleRate / 2)
            @Suppress("MissingPermission")
            audio = AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf
            )
            audio?.startRecording()
        }
        return audio?.recordingState == AudioRecord.RECORDSTATE_RECORDING
    }

    /** Average loudness (RMS) over roughly [ms] milliseconds. */
    private suspend fun rms(ms: Int): Double = withContext(Dispatchers.IO) {
        val rec = audio ?: return@withContext 0.0
        val want = sampleRate * ms / 1000
        val buf = ShortArray(2048)
        var sum = 0.0; var n = 0
        while (n < want) {
            val r = rec.read(buf, 0, buf.size)
            if (r <= 0) break
            for (i in 0 until r) sum += buf[i].toDouble() * buf[i]
            n += r
        }
        if (n == 0) 0.0 else sqrt(sum / n)
    }

    override fun onDestroy() {
        super.onDestroy()
        audio?.release(); audio = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lab)
        ir = SonyIrBlaster(this)

        val probe = findViewById<TextView>(R.id.labProbe)
        val counter = findViewById<TextView>(R.id.labCounter)
        val logView = findViewById<TextView>(R.id.labLog)
        val addrIn = findViewById<EditText>(R.id.labAddr)
        val cmdIn = findViewById<EditText>(R.id.labCmd)
        val bitsIn = findViewById<EditText>(R.id.labBits)
        val extIn = findViewById<EditText>(R.id.labExt)
        val startBtn = findViewById<Button>(R.id.labStartSweep)
        val mapBtn = findViewById<Button>(R.id.labMapCmds)
        val stopBtn = findViewById<Button>(R.id.labStop)

        fun ui(active: Boolean) {
            stopBtn.isEnabled = active
            startBtn.isEnabled = !active
            mapBtn.isEnabled = !active
        }

        fun record(s: Sent) {
            recent.addLast(s)
            if (recent.size > 6) recent.removeFirst()
            sentCount++
            probe.text = "S${s.bits}  addr ${s.addr}${if (s.bits == 20) "." + s.ext else ""}  cmd ${s.cmd}"
            counter.text = "$sentCount sent"
        }

        val autoBox = findViewById<CheckBox>(R.id.labAutoDetect)
        autoBox.setOnCheckedChangeListener { _, checked ->
            if (checked && !micReady()) {
                Toast.makeText(this, "Grant mic permission, then re-tick", Toast.LENGTH_LONG).show()
                autoBox.isChecked = false
            }
        }

        suspend fun fire(bits: Int, addr: Int, ext: Int, cmd: Int, gapMs: Long) {
            if (autoBox.isChecked && micReady()) {
                // AUTO: loudness before → send → loudness after; big change = hit
                val base = rms(220)
                try { ir.send(cmd, addr, bits, ext) } catch (_: Exception) {}
                record(Sent(bits, addr, ext, cmd))
                delay(380)
                val post = rms(220)
                val delta = if (base > 40) (post - base) / base else 0.0
                if (base > 40 && abs(delta) > 0.45) {
                    logLines.append("$ts,AUTO_HIT,$bits,$addr,$ext,$cmd,audio change ${"%.0f".format(delta * 100)}%\n")
                    logView.append("AUTO ✓ S$bits $addr/$cmd (${"%.0f".format(delta * 100)}%)\n")
                }
                delay(150)
            } else {
                try { ir.send(cmd, addr, bits, ext) } catch (_: Exception) {}
                record(Sent(bits, addr, ext, cmd))
                delay(gapMs)
            }
        }

        // PHASE 1 — find responding addresses: probe well-known commands on every address
        startBtn.setOnClickListener {
            ui(true)
            job = lifecycleScope.launch {
                try {
                    logLines.append("$ts,sweep_start,,,,,address sweep S15+S12\n")
                    val probes = listOf(18, 21, 111, 50)   // vol+, power, band, play
                    for (a in 0..255) for (c in probes) fire(15, a, 0, c, 350)
                    for (a in 0..31) for (c in probes) fire(12, a, 0, c, 350)
                    logLines.append("$ts,sweep_end,,,,,\n")
                } finally { ui(false) }
            }
        }

        // PHASE 2 — map all commands 0..127 on one address (from the addr/bits/ext fields)
        mapBtn.setOnClickListener {
            val a = addrIn.text.toString().toIntOrNull() ?: return@setOnClickListener
            val b = bitsIn.text.toString().toIntOrNull() ?: 15
            val e = extIn.text.toString().toIntOrNull() ?: 0
            ui(true)
            job = lifecycleScope.launch {
                try {
                    logLines.append("$ts,map_start,$b,$a,$e,,command map 0-127\n")
                    for (c in 0..127) fire(b, a, e, c, 600)
                    logLines.append("$ts,map_end,$b,$a,$e,,\n")
                } finally { ui(false) }
            }
        }

        // iPOD HUNT — every command (0-127) on the three most likely transport
        // addresses: 100 (this unit's tuner/transport), 153 (Sony iPod dock
        // speaker), and Sony20 26.19 (Sony dock radios). Dock the iPod, play
        // music, watch its screen — MARK the instant it lights up or skips.
        findViewById<Button>(R.id.labIpodHunt).setOnClickListener {
            ui(true)
            job = lifecycleScope.launch {
                try {
                    logLines.append("$ts,ipod_hunt_start,,,,,addr 100 + 153 (S15) + 26.19 (S20) all cmds\n")
                    for (c in 0..127) fire(15, 100, 0, c, 600)
                    logLines.append("$ts,ipod_hunt,,,,,finished addr 100 - starting 153\n")
                    for (c in 0..127) fire(15, 153, 0, c, 600)
                    logLines.append("$ts,ipod_hunt,,,,,finished addr 153 - starting 26.19 S20\n")
                    for (c in 0..127) fire(20, 26, 19, c, 600)
                    logLines.append("$ts,ipod_hunt_end,,,,,\n")
                } finally { ui(false) }
            }
        }

        // DEEP HUNT — full command maps on the remaining candidate addresses:
        // 101 (S15, appeared in the first sweep's MARK buffer), Sony20 26.57
        // (modern mini-system set with MENU/UP/DOWN/ENTER), Sony20 26.153,
        // neighbors 69 & 99 (S15), and classic amp/CD 16 & 17 (S12). ~9 min.
        findViewById<Button>(R.id.labDeepHunt).setOnClickListener {
            ui(true)
            job = lifecycleScope.launch {
                try {
                    val phases = listOf(
                        Triple(15, 101, 0), Triple(20, 26, 57), Triple(20, 26, 153),
                        Triple(15, 69, 0), Triple(15, 99, 0),
                        Triple(12, 16, 0), Triple(12, 17, 0)
                    )
                    for ((b, a, e) in phases) {
                        logLines.append("$ts,deep_hunt,,,,,starting addr $a${if (b == 20) ".$e" else ""} (S$b) all cmds\n")
                        for (c in 0..127) fire(b, a, e, c, 600)
                    }
                    logLines.append("$ts,deep_hunt_end,,,,,\n")
                } finally { ui(false) }
            }
        }

        // POWER HUNT — slowly fires the most likely power on/off candidates.
        // Watch the unit; when it shuts off (or wakes), hit STOP — the display
        // shows the code that did it.
        findViewById<Button>(R.id.labPowerHunt).setOnClickListener {
            ui(true)
            job = lifecycleScope.launch {
                try {
                    logLines.append("$ts,power_hunt_start,,,,,\n")
                    // (bits, addr, ext, cmd) — Sony power/standby codes seen across
                    // device families, plus this unit's own unexplored neighbors
                    val candidates = listOf(
                        Triple(15, 68, 21),   // standard Sony power
                        Triple(15, 68, 97),   // standby
                        Triple(15, 68, 46),   // discrete power on
                        Triple(15, 68, 47),   // discrete power off
                        Triple(15, 68, 15),
                        Triple(15, 68, 16),
                        Triple(15, 68, 91),
                        Triple(15, 68, 122),
                        Triple(15, 68, 123),
                        Triple(15, 68, 86),   // neighbor of 87 (exit audio-in)
                        Triple(15, 68, 88),
                        Triple(15, 100, 21),
                        Triple(15, 100, 97),
                        Triple(12, 16, 21),   // Sony amp power (S12)
                        Triple(12, 16, 47),
                        Triple(15, 68, 49)    // the old mystery cmd
                    )
                    for ((b, a, c) in candidates) fire(b, a, 0, c, 2000)
                    logLines.append("$ts,power_hunt_end,,,,,\n")
                } finally { ui(false) }
            }
        }

        stopBtn.setOnClickListener {
            job?.cancel(); ui(false)
            logLines.append("$ts,stopped,,,,,at ${probe.text}\n")
            logView.append("STOP @ ${probe.text}\n")
        }

        // MARK — the radio just reacted: log the last few codes sent (covers reaction lag)
        findViewById<Button>(R.id.labMark).setOnClickListener {
            val snapshot = recent.toList()
            if (snapshot.isEmpty()) return@setOnClickListener
            logLines.append("$ts,MARK,,,,,radio reacted - last ${snapshot.size} codes follow\n")
            for (s in snapshot.reversed())
                logLines.append("$ts,candidate,${s.bits},${s.addr},${s.ext},${s.cmd},\n")
            val newest = snapshot.last()
            logView.append("MARK → S${newest.bits} ${newest.addr}/${newest.cmd} (+${snapshot.size - 1} before)\n")
            Toast.makeText(this, "Marked", Toast.LENGTH_SHORT).show()
        }

        // Manual single send + WORKS logging with a label
        findViewById<Button>(R.id.labSend).setOnClickListener {
            val a = addrIn.text.toString().toIntOrNull() ?: return@setOnClickListener
            val c = cmdIn.text.toString().toIntOrNull() ?: return@setOnClickListener
            val b = bitsIn.text.toString().toIntOrNull() ?: 15
            val e = extIn.text.toString().toIntOrNull() ?: 0
            try { ir.send(c, a, b, e); record(Sent(b, a, e, c)) }
            catch (ex: Exception) { Toast.makeText(this, "TX failed: ${ex.message}", Toast.LENGTH_SHORT).show() }
        }
        findViewById<Button>(R.id.labPlus).setOnClickListener {
            cmdIn.setText((((cmdIn.text.toString().toIntOrNull() ?: 0) + 1).coerceAtMost(127)).toString())
            findViewById<Button>(R.id.labSend).performClick()
        }
        findViewById<Button>(R.id.labMinus).setOnClickListener {
            cmdIn.setText((((cmdIn.text.toString().toIntOrNull() ?: 0) - 1).coerceAtLeast(0)).toString())
            findViewById<Button>(R.id.labSend).performClick()
        }
        findViewById<Button>(R.id.labWorks).setOnClickListener {
            val a = addrIn.text.toString().toIntOrNull() ?: return@setOnClickListener
            val c = cmdIn.text.toString().toIntOrNull() ?: return@setOnClickListener
            val b = bitsIn.text.toString().toIntOrNull() ?: 15
            val e = extIn.text.toString().toIntOrNull() ?: 0
            val label = findViewById<EditText>(R.id.labLabel).text.toString().replace(",", " ")
            logLines.append("$ts,WORKS,$b,$a,$e,$c,$label\n")
            logView.append("WORKS ✓ S$b $a/$c = $label\n")
            Toast.makeText(this, "Logged: $label", Toast.LENGTH_SHORT).show()
        }

        // SAVE — write CSV into Downloads
        findViewById<Button>(R.id.labSave).setOnClickListener {
            val name = "cs15ip_ir_log_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val cv = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    contentResolver.openOutputStream(uri!!)!!.use {
                        it.write(logLines.toString().toByteArray())
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    java.io.File(dir, name).writeText(logLines.toString())
                }
                Toast.makeText(this, "Saved to Downloads/$name", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        // COPY — put the whole log on the clipboard (paste it into a chat)
        findViewById<Button>(R.id.labCopy).setOnClickListener {
            val cb = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cb.setPrimaryClip(android.content.ClipData.newPlainText("CS15iP IR log", logLines.toString()))
            Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        // SHARE — send the log anywhere (chat, email, notes)
        findViewById<Button>(R.id.labShare).setOnClickListener {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "CS15iP IR discovery log")
                putExtra(Intent.EXTRA_TEXT, logLines.toString())
            }
            startActivity(Intent.createChooser(i, "Share IR log"))
        }
    }
}
