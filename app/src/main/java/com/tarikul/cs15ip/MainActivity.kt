package com.tarikul.cs15ip

import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.GridLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Remote + discovery UI for the Sony ICF-CS15iP.
 *
 * The real codes for this unit are unpublished. Four candidate Sony SIRC
 * code sets (taken from sibling Sony products) are wired to the buttons.
 * If none work, the sweep modes probe the whole SIRC space until the unit
 * reacts — the address/cmd shown at that moment is the real code.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var ir: SonyIrBlaster
    private var sweepJob: Job? = null

    /** name, bits, address, extended, buttons(label -> command) */
    private data class Candidate(
        val name: String,
        val bits: Int,
        val address: Int,
        val extended: Int,
        val buttons: List<Pair<String, Int>>
    )

    private val candidates = listOf(
        Candidate(
            "1) Sony15 dev 68 — clock radio (RMT-C350/CS200A)", 15, 68, 0,
            listOf(
                "Power" to 21, "Vol +" to 18, "Vol -" to 19,
                "Mute" to 20, "Sleep" to 96, "Standby" to 97,
                "Function" to 71, "Mode" to 17, "Sound" to 48
            )
        ),
        Candidate(
            "2) Sony15 dev 100 — boombox transport/tuner", 15, 100, 0,
            listOf(
                "Play" to 50, "Pause" to 57, "Stop" to 56,
                "Track +" to 49, "Track -" to 48, "Band" to 111,
                "Tune +" to 115, "Tune -" to 116, "Display" to 28
            )
        ),
        Candidate(
            "3) Sony15 dev 153 — SRS-GU10iP iPod dock", 15, 153, 0,
            listOf(
                "Power" to 21, "Vol +" to 18, "Vol -" to 19,
                "Mute" to 20, "Play" to 40, "Next" to 41,
                "Prev" to 42, "Input" to 32, "Bass" to 49
            )
        ),
        Candidate(
            "4) Sony20 dev 26.19 — ICF-CDK50/XDR radios", 20, 26, 19,
            listOf(
                "Power/Band" to 111, "Vol +" to 18, "Vol -" to 19,
                "Mute" to 20, "Play/Pause" to 25, "Stop" to 24,
                "Next" to 27, "Prev" to 26, "Menu" to 72
            )
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ir = SonyIrBlaster(this)

        val status = findViewById<TextView>(R.id.statusText)
        status.text = if (ir.hasIrEmitter)
            "IR emitter: OK — point the top of the phone at the radio"
        else
            "NO IR EMITTER on this phone — the app cannot transmit"

        val spinner = findViewById<Spinner>(R.id.candidateSpinner)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            candidates.map { it.name }
        )
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                buildButtons(candidates[pos])
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        buildButtons(candidates[0])

        val probe = findViewById<TextView>(R.id.probeText)
        val log = findViewById<TextView>(R.id.logText)
        val stopBtn = findViewById<Button>(R.id.stopButton)

        fun setSweeping(active: Boolean) {
            stopBtn.isEnabled = active
            findViewById<Button>(R.id.sweepCmdsButton).isEnabled = !active
            findViewById<Button>(R.id.bruteButton).isEnabled = !active
        }

        findViewById<Button>(R.id.sweepCmdsButton).setOnClickListener {
            val c = candidates[spinner.selectedItemPosition]
            setSweeping(true)
            sweepJob = lifecycleScope.launch {
                try {
                    ir.sweep(c.address, c.bits, c.extended) { cmd ->
                        probe.text = "addr ${c.address}  cmd $cmd"
                    }
                    log.append("Finished cmd sweep on addr ${c.address} (${c.bits}-bit)\n")
                } finally { setSweeping(false) }
            }
        }

        findViewById<Button>(R.id.bruteButton).setOnClickListener {
            setSweeping(true)
            sweepJob = lifecycleScope.launch {
                try {
                    // Probe Power/Vol+/Band-style cmds across every SIRC15 address,
                    // then every SIRC12 address.
                    ir.bruteForceAddresses(15) { a, cmd -> probe.text = "S15 addr $a cmd $cmd" }
                    ir.bruteForceAddresses(12) { a, cmd -> probe.text = "S12 addr $a cmd $cmd" }
                    log.append("Brute force finished with no manual stop\n")
                } finally { setSweeping(false) }
            }
        }

        stopBtn.setOnClickListener {
            sweepJob?.cancel()
            log.append("STOPPED at: ${probe.text}  <-- your device code is at/near this value\n")
            setSweeping(false)
        }
    }

    private fun buildButtons(c: Candidate) {
        val grid = findViewById<GridLayout>(R.id.buttonGrid)
        grid.removeAllViews()
        for ((label, cmd) in c.buttons) {
            val b = Button(this)
            b.text = label
            val lp = GridLayout.LayoutParams()
            lp.width = 0
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            lp.setMargins(4, 4, 4, 4)
            b.layoutParams = lp
            b.setOnClickListener {
                if (!ir.hasIrEmitter) {
                    Toast.makeText(this, "No IR emitter", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                try {
                    ir.send(cmd, c.address, c.bits, c.extended)
                } catch (e: Exception) {
                    Toast.makeText(this, "TX failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            grid.addView(b)
        }
    }
}
