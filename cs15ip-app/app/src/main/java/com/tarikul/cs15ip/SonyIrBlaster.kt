package com.tarikul.cs15ip

import android.content.Context
import android.hardware.ConsumerIrManager
import kotlinx.coroutines.delay

/**
 * Sony SIRC transmitter for Android IR blasters — built for the Sony ICF-CS15iP
 * (remote RMT-CCS15iP, codes not published anywhere; discover them with sweep mode).
 *
 * SIRC basics: 40 kHz carrier. Header 2400us mark + 600us space.
 * Bit "1" = 1200us mark + 600us space. Bit "0" = 600us mark + 600us space.
 * LSB first: 7 command bits, then 5 address bits (SIRC12), 8 address bits (SIRC15),
 * or 5 address + 8 extended bits (SIRC20). Frame repeats every 45 ms; send 3x minimum.
 */
class SonyIrBlaster(context: Context) {

    private val irManager =
        context.getSystemService(Context.CONSUMER_IR_SERVICE) as ConsumerIrManager

    val hasIrEmitter: Boolean get() = irManager.hasIrEmitter()

    companion object {
        const val FREQ = 40_000          // SIRC carrier
        const val HDR_MARK = 2400
        const val SPACE = 600
        const val ONE_MARK = 1200
        const val ZERO_MARK = 600
        const val FRAME_PERIOD_US = 45_000
        const val REPEATS = 3            // Sony devices expect >= 3 frames
    }

    /** Build one SIRC frame as alternating mark/space microsecond durations. */
    private fun buildFrame(command: Int, address: Int, extended: Int, bits: Int): IntArray {
        // Assemble the bit stream LSB-first: 7 cmd bits, then address, then extended
        val stream = ArrayList<Int>(bits)
        for (i in 0 until 7) stream.add((command shr i) and 1)
        val addrBits = when (bits) { 12 -> 5; 15 -> 8; 20 -> 5; else -> error("bits must be 12/15/20") }
        for (i in 0 until addrBits) stream.add((address shr i) and 1)
        if (bits == 20) for (i in 0 until 8) stream.add((extended shr i) and 1)

        val pattern = ArrayList<Int>(2 + bits * 2)
        pattern.add(HDR_MARK); pattern.add(SPACE)
        var frameLen = HDR_MARK + SPACE
        for (b in stream) {
            val mark = if (b == 1) ONE_MARK else ZERO_MARK
            pattern.add(mark); pattern.add(SPACE)
            frameLen += mark + SPACE
        }
        // Pad the final space so the whole frame occupies the 45 ms repeat period
        val pad = FRAME_PERIOD_US - frameLen
        if (pad > 0) pattern[pattern.size - 1] = pattern.last() + pad
        return pattern.toIntArray()
    }

    /** Transmit a SIRC code with the standard 3 repeats. */
    fun send(command: Int, address: Int, bits: Int, extended: Int = 0) {
        val frame = buildFrame(command, address, extended, bits)
        val full = IntArray(frame.size * REPEATS)
        for (r in 0 until REPEATS) frame.copyInto(full, r * frame.size)
        irManager.transmit(FREQ, full)
    }

    // ---- Candidate code sets for the ICF-CS15iP (from sibling Sony products) ----

    /** Candidate 1 — Sony15 device 68 (Sony clock-radio remotes RMT-C350 / RMT-CS200A). */
    fun c1(cmd: Int) = send(cmd, 68, 15)
    // Known cmds: 21=Power, 18=Vol+, 19=Vol-, 20=Mute, 96=Sleep, 71=Function, 17=Mode

    /** Candidate 2 — Sony15 device 100 (boombox transport / tuner set). */
    fun c2(cmd: Int) = send(cmd, 100, 15)
    // Known cmds: 50=Play, 56=Stop, 57=Pause, 48=Track-, 49=Track+, 111=Band, 115=Tune+, 116=Tune-

    /** Candidate 3 — Sony15 device 153 (SRS-GU10iP iPod dock speaker). */
    fun c3(cmd: Int) = send(cmd, 153, 15)
    // Known cmds: 21=Power, 20=Mute, 18=Vol+, 19=Vol-, 40=Play, 41=Next, 42=Prev, 32=Input

    /** Candidate 4 — Sony20 device 26 ext 19 (ICF-CDK50 clock radio / XDR dock radios). */
    fun c4(cmd: Int) = send(cmd, 26, 20, extended = 19)
    // Known cmds: 111=Power/Band, 25=Play/Pause, 24=Stop, 27=Next, 26=Prev, 18=Vol+, 19=Vol-, 20=Mute

    /**
     * DISCOVERY SWEEP — point the phone at the unit and run this.
     * Sends Power/Vol+ style probe commands across a command range for one
     * (protocol, address) pair, with a gap so you can see which one reacts.
     * Log/display the current (address, cmd) as it goes; when the unit powers
     * on or the volume moves, note the value shown — that's your device code.
     */
    suspend fun sweep(
        address: Int,
        bits: Int,
        extended: Int = 0,
        commands: IntRange = 0..127,
        gapMs: Long = 350,
        onProbe: (Int) -> Unit = {}
    ) {
        for (cmd in commands) {
            onProbe(cmd)
            send(cmd, address, bits, extended)
            delay(gapMs)
        }
    }

    /**
     * Full brute force if all four candidates fail:
     * SIRC12 -> addresses 0..31, SIRC15 -> 0..255, SIRC20 -> address 0..31 x ext 0..255.
     * Probing only cmd 21 (Power) and cmd 18 (Vol+) per address keeps a full
     * SIRC15 sweep under ~4 minutes at 400 ms per probe.
     */
    suspend fun bruteForceAddresses(
        bits: Int,
        probes: List<Int> = listOf(21, 18, 111),
        gapMs: Long = 400,
        onProbe: (addr: Int, cmd: Int) -> Unit = { _, _ -> }
    ) {
        val addrMax = if (bits == 15) 255 else 31
        for (addr in 0..addrMax) for (cmd in probes) {
            onProbe(addr, cmd)
            send(cmd, addr, bits)
            delay(gapMs)
        }
    }
}
