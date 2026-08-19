package com.tarikul.cs15ip

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Simple remote for the Sony ICF-CS15iP — confirmed working codes only.
 *
 * Discovered empirically (Aug 2026), Sony SIRC 15-bit @ 40 kHz:
 *   device 68  : Vol+ (18), Vol- (19), Sound (48), cmd 49, Sleep (96)
 *   device 100 : Band (111), Tune+ (115), Tune- (116) — radio mode
 */
class MainActivity : AppCompatActivity() {

    private lateinit var ir: SonyIrBlaster

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ir = SonyIrBlaster(this)

        // Machine photo (embedded), rounded corners
        val machine = findViewById<android.widget.ImageView>(R.id.machineImage)
        machine.setImageBitmap(MachineImage.bitmap())
        machine.clipToOutline = true

        val status = findViewById<TextView>(R.id.statusText)
        if (ir.hasIrEmitter) {
            status.text = "● IR READY"
        } else {
            status.text = "● NO IR EMITTER"
            status.setTextColor(0xFFE0605E.toInt())
        }

        bindRepeating(R.id.volUpButton, 18, 68)
        bindRepeating(R.id.volDownButton, 19, 68)
        bind(R.id.soundButton, 48, 68)   // toggles Mega Bass / MegaXpand
        bind(R.id.sleepButton, 96, 68)
        bind(R.id.bandButton, 111, 100)
        bind(R.id.tuneUpButton, 115, 100)
        bind(R.id.tuneDownButton, 116, 100)
        bind(R.id.pauseButton, 67, 68)   // Audio In ON
        bind(R.id.offButton, 87, 68)     // Audio In OFF
        // tap = prev/next track; hold ~0.5s = keep sending (scrubs inside the song)
        bindRepeating(R.id.nextButton, 68, 100, holdDelayMs = 450)
        bindRepeating(R.id.rewButton, 67, 100, holdDelayMs = 450)
        bind(R.id.playPauseButton, 51, 100) // play/pause toggle

        findViewById<View>(R.id.labOpenButton).setOnClickListener {
            startActivity(android.content.Intent(this, LabActivity::class.java))
        }
    }

    private fun bind(viewId: Int, cmd: Int, address: Int) {
        findViewById<View>(viewId).setOnClickListener {
            if (!ir.hasIrEmitter) {
                Toast.makeText(this, "No IR emitter", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                ir.send(cmd, address, 15)
                haptic()
            } catch (e: Exception) {
                Toast.makeText(this, "TX failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Tap = one send. Hold = after [holdDelayMs], keep re-sending every 150 ms
     * (volume ramp, or scrubbing inside a song on prev/next).
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindRepeating(viewId: Int, cmd: Int, address: Int, holdDelayMs: Long = 0) {
        var repeatJob: Job? = null
        findViewById<View>(viewId).setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!ir.hasIrEmitter) {
                        Toast.makeText(this, "No IR emitter", Toast.LENGTH_SHORT).show()
                        return@setOnTouchListener true
                    }
                    v.isPressed = true
                    haptic()
                    repeatJob = lifecycleScope.launch {
                        try { ir.send(cmd, address, 15) } catch (_: Exception) {}
                        if (holdDelayMs > 0) delay(holdDelayMs)
                        // While held: auto-repeat DISCRETE presses with a gap, exactly
                        // like spamming the button by hand — on this unit that skips
                        // ~10 s inside the song per press. Continuous streams (no gap)
                        // make this dock jump whole tracks, so never do that.
                        while (isActive) {
                            delay(200)
                            try { ir.send(cmd, address, 15) } catch (_: Exception) {}
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    repeatJob?.cancel(); repeatJob = null
                    true
                }
                else -> false
            }
        }
    }

    private fun haptic() {
        val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 26)
            v.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") v.vibrate(15)
    }
}
