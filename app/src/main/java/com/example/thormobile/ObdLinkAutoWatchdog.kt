package com.example.thormobile

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.util.LinkedList
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

object ObdLinkAutoWatchdog {
    private const val TAG = "ObdLinkEdgeAgent"
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val CR_STR = 13.toChar().toString()
    private val LF_STR = 10.toChar().toString()

    @Volatile private var running = false
    @Volatile var cloudWatchdogMode: String = "auto"
    @Volatile var pendingServerCmd: String = ""
    private val ringBuffer = LinkedList<String>()
    private val reportedFaultsThisDrive = HashSet<String>()
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private fun parseServerStateJson(txt: String) {
        if (txt.isBlank()) return
        try {
            val root = JSONObject(txt)
            val liveObj = root.optJSONObject("live_state") ?: root
            val m = liveObj.optString("mode", root.optString("watchdog_mode", "")).lowercase().trim()
            if (m == "off" || m == "live" || m == "auto") {
                cloudWatchdogMode = m
            }
            val pCmd = liveObj.optString("pending_cmd", "").uppercase().trim()
            if (pCmd == "CLEAR_DTCS" || pCmd == "FULL_SCAN" || pCmd == "ROAD_TEST_30S") {
                pendingServerCmd = pCmd
            }
        } catch (_: Exception) {}
    }

    private fun syncCloudWatchdogStatus(serverBaseUrl: String) {
        try {
            val cleanBase = serverBaseUrl.trimEnd('/')
            val modeReq = Request.Builder()
                .url("$cleanBase/api/obd-live?action=status&t=${System.currentTimeMillis()}")
                .header("User-Agent", "ThorObdWatchdog/18.3 (Samsung-Note20Ultra)")
                .get()
                .build()
            http.newCall(modeReq).execute().use { r ->
                val txt = r.body?.string() ?: ""
                parseServerStateJson(txt)
            }
        } catch (_: Exception) {}
    }

    private fun ackServerCmd(serverBaseUrl: String, message: String) {
        try {
            val cleanBase = serverBaseUrl.trimEnd('/')
            val qMsg = URLEncoder.encode(message, "UTF-8")
            val req = Request.Builder()
                .url("$cleanBase/api/obd-live?action=ack_cmd&msg=$qMsg&t=${System.currentTimeMillis()}")
                .header("User-Agent", "ThorObdWatchdog/18.3 (Samsung-Note20Ultra)")
                .get()
                .build()
            http.newCall(req).execute().close()
        } catch (_: Exception) {}
    }

    private fun executeFullDtcAndMode06Scan(
        serverBaseUrl: String,
        outs: OutputStream,
        ins: InputStream,
        detectedVin: String,
        isHemiV8: Boolean,
        currentFrame: String,
        reasonPrefix: String
    ): String {
        val m03 = sendAtCommand(outs, ins, "03", 2200L)
        val m07 = sendAtCommand(outs, ins, "07", 2200L)
        val m0A = sendAtCommand(outs, ins, "0A", 2200L)
        val dtcBundle = formatDtcBundle(m03, m07, m0A)

        val activeMisfires = ArrayList<String>()
        val maxCyl = if (isHemiV8) 8 else 6
        for (cyl in 1..maxCyl) {
            val midHex = "A" + (cyl + 1)
            val m06Raw = sendAtCommand(outs, ins, "06$midHex", 1400L)
            val cnt = extractMode06MisfireCount(m06Raw, midHex)
            if (cnt > 0) {
                activeMisfires.add("Cyl$cyl:$cnt")
            }
        }
        val misfireTag = if (activeMisfires.isEmpty()) "Mode06 Misfires: 0" else "Mode06 Active Misfires: ${activeMisfires.joinToString(",")}"
        val fullSummary = "DTC $reasonPrefix — $dtcBundle | $misfireTag"
        reportToThor(
            serverBaseUrl,
            detectedVin,
            fullSummary,
            currentFrame.ifEmpty { "KOEO Diagnostic Mode | $dtcBundle" },
            saveToDb = true
        )
        ackServerCmd(serverBaseUrl, fullSummary)
        return fullSummary
    }

    private fun execute30SecRoadTestFlightRecorder(
        serverBaseUrl: String,
        outs: OutputStream,
        ins: InputStream,
        detectedVin: String,
        isHemiV8: Boolean,
        unsupportedMode22: MutableSet<String>
    ) {
        Log.i(TAG, "WO-150: Starting 30-Second High-Speed Road-Test Flight Recorder...")
        val maxCyl = if (isHemiV8) 8 else 6
        val preCyl = IntArray(maxCyl + 1)
        for (c in 1..maxCyl) {
            val mid = "A" + (c + 1)
            preCyl[c] = extractMode06MisfireCount(sendAtCommand(outs, ins, "06$mid", 950L), mid)
        }

        val tStart = System.currentTimeMillis()
        var frames = 0
        var minRpm = 9999.0
        var maxRpm = 0.0
        var steadyCruiseRpmJitter = 0.0
        var prevRpm = -1.0
        var prevMph = -1
        var prevTps = -1.0
        var maxTccSlip = 0.0
        var maxTransF = 0.0
        var minRailPsi = 9999.0
        var maxRailPsi = 0.0
        var maxAbsStft = 0.0
        var maxKrDeg = 0.0
        var mafGlitchesInTest = 0
        var lastSnapshot = ""

        while (running && (System.currentTimeMillis() - tStart) < 30_000L) {
            val rpm = decodeRpmOrNull(sendAtCommand(outs, ins, "010C", 500L)) ?: 0.0
            val mph = ((decodeSingleByteOrNull(sendAtCommand(outs, ins, "010D", 450L), "410D") ?: 0) * 0.621371).toInt()
            val tps = decodePctByte(sendAtCommand(outs, ins, "0111", 450L), "4111")
            val load = decodePctByte(sendAtCommand(outs, ins, "0104", 450L), "4104")
            val mapK = decodeSingleByteOrNull(sendAtCommand(outs, ins, "010B", 450L), "410B") ?: 0
            val st1 = decodeFuelTrimPct(sendAtCommand(outs, ins, "0106", 450L), "4106")
            val lt1 = decodeFuelTrimPct(sendAtCommand(outs, ins, "0107", 450L), "4107")
            val st2 = decodeFuelTrimPct(sendAtCommand(outs, ins, "0108", 450L), "4108")
            val lt2 = decodeFuelTrimPct(sendAtCommand(outs, ins, "0109", 450L), "4109")
            val maf = decodeMafGpsOrNull(sendAtCommand(outs, ins, "0110", 450L))
            if (maf != null && maf <= 0.05 && rpm >= 500.0 && mapK >= 20) mafGlitchesInTest++

            val railRaw = sendAtCommand(outs, ins, "0123", 450L)
            val railPsi = decodeGdiFuelRailPsiOrNull(railRaw)
            if (railPsi != null && railPsi > 40.0) {
                minRailPsi = min(minRailPsi, railPsi)
                maxRailPsi = max(maxRailPsi, railPsi)
            }

            if (!isHemiV8 && frames % 3 == 0) {
                val enh = pollGmEnhancedMode22Safe(outs, ins, unsupportedMode22)
                enh.transTempF?.let { maxTransF = max(maxTransF, it) }
                enh.tccSlipRpm?.let { maxTccSlip = max(maxTccSlip, abs(it)) }
                enh.knockRetardDeg?.let { maxKrDeg = max(maxKrDeg, it) }
            }

            if (rpm > 0.0) {
                minRpm = min(minRpm, rpm)
                maxRpm = max(maxRpm, rpm)
            }
            maxAbsStft = max(maxAbsStft, max(abs(st1), abs(st2)))
            if (prevRpm > 0.0 && mph in 32..72 && abs(mph - prevMph) <= 1 && abs(tps - prevTps) <= 2.2) {
                steadyCruiseRpmJitter = max(steadyCruiseRpmJitter, abs(rpm - prevRpm))
            }
            prevRpm = rpm
            prevMph = mph
            prevTps = tps
            frames++
            lastSnapshot = "RPM: ${rpm.toInt()} | Speed: ${mph}MPH | TPS: ${tps.toInt()}% | Load: ${load.toInt()}% | MAP: ${mapK}kPa | B1: ST ${st1}%/LT ${lt1}% | B2: ST ${st2}%/LT ${lt2}% | MAF: ${maf ?: 0.0}g/s"
        }

        val cylDeltas = ArrayList<String>()
        for (c in 1..maxCyl) {
            val mid = "A" + (c + 1)
            val post = extractMode06MisfireCount(sendAtCommand(outs, ins, "06$mid", 950L), mid)
            val delta = max(0, post - preCyl[c])
            if (delta > 0 || post > 0) {
                cylDeltas.add("Cyl$c:+${delta}(tot:$post)")
            }
        }
        val m07 = decodeDtcList(sendAtCommand(outs, ins, "07", 1800L), "47")
        val railSummary = if (maxRailPsi > 0.0 && minRailPsi < 9000.0) "${minRailPsi.toInt()}-${maxRailPsi.toInt()}PSI" else "N/A"
        val recorderDigest = "$lastSnapshot | 30sFrames:$frames | RPMRange:${if (minRpm < 9000) minRpm.toInt() else 0}-${maxRpm.toInt()} | CruiseRPMJitter:${steadyCruiseRpmJitter.toInt()}RPM | TCCSlipPeak:${maxTccSlip.toInt()}RPM | TransTemp:${if (maxTransF > 0) "${maxTransF.toInt()}F" else "N/A"} | RailPSI:$railSummary | PeakSTFT:${maxAbsStft}% | KR:${maxKrDeg}deg | MAFGlitches:$mafGlitchesInTest | MisfireDelta:${if (cylDeltas.isEmpty()) "0" else cylDeltas.joinToString(",")} | Pending07:${if (m07.isEmpty()) "None" else m07.joinToString(",")}"

        val trigLabel = "Road-Test 30s Flight Recorder Complete"
        reportToThor(serverBaseUrl, detectedVin, trigLabel, recorderDigest, saveToDb = true)
        ackServerCmd(serverBaseUrl, "$trigLabel -> $recorderDigest")
    }

    private data class GmEnhancedSnapshot(
        val transTempF: Double? = null,
        val tccSlipRpm: Double? = null,
        val camErrDeg: Double? = null,
        val knockRetardDeg: Double? = null
    )

    private fun pollGmEnhancedMode22Safe(
        outs: OutputStream,
        ins: InputStream,
        unsupported: MutableSet<String>
    ): GmEnhancedSnapshot {
        var transF: Double? = null
        var tccSlip: Double? = null
        var camErr: Double? = null
        var krDeg: Double? = null
        try {
            // 1. Query 6T70 TCM (0x7E2) for Transmission Fluid Temp (221940) & TCC Slip Speed (221992)
            if (!unsupported.contains("7E2_ALL")) {
                sendAtCommand(outs, ins, "ATSH 7E2", 350L)
                if (!unsupported.contains("221940")) {
                    val rawTft = sendAtCommand(outs, ins, "221940", 700L)
                    if (hasValidEcuHex(rawTft, "621940")) {
                        val b = extractDataBytes(rawTft, "621940")
                        if (b.isNotEmpty()) {
                            val c = (b[0] - 40).toDouble()
                            val f = round(((c * 9.0 / 5.0) + 32.0) * 10.0) / 10.0
                            if (f in -20.0..320.0) transF = f
                        }
                    } else if (rawTft.uppercase().contains("7F22") || rawTft.uppercase().contains("NO DATA")) {
                        unsupported.add("221940")
                    }
                }
                if (!unsupported.contains("221992")) {
                    val rawSlip = sendAtCommand(outs, ins, "221992", 700L)
                    if (hasValidEcuHex(rawSlip, "621992")) {
                        val b = extractDataBytes(rawSlip, "621992")
                        if (b.size >= 2) {
                            val signedRaw = ((b[0] shl 8) or b[1]).toShort().toInt()
                            val scaled = round((signedRaw / 8.0) * 10.0) / 10.0
                            if (abs(scaled) <= 2500.0) tccSlip = scaled
                        }
                    } else if (rawSlip.uppercase().contains("7F22") || rawSlip.uppercase().contains("NO DATA")) {
                        unsupported.add("221992")
                    }
                }
                if (unsupported.contains("221940") && unsupported.contains("221992")) {
                    unsupported.add("7E2_ALL")
                }
            }

            // 2. Query 3.6L LFX E39A ECM (0x7E0) for Knock Retard & Cam Phaser Error
            if (!unsupported.contains("7E0_ALL")) {
                sendAtCommand(outs, ins, "ATSH 7E0", 350L)
                if (!unsupported.contains("2211A6")) {
                    val rawKr = sendAtCommand(outs, ins, "2211A6", 650L)
                    if (hasValidEcuHex(rawKr, "6211A6")) {
                        val b = extractDataBytes(rawKr, "6211A6")
                        if (b.isNotEmpty()) {
                            krDeg = round(((b[0] * 22.5) / 256.0) * 10.0) / 10.0
                        }
                    } else if (rawKr.uppercase().contains("7F22") || rawKr.uppercase().contains("NO DATA")) {
                        unsupported.add("2211A6")
                    }
                }
                if (!unsupported.contains("221602")) {
                    val rawCam = sendAtCommand(outs, ins, "221602", 650L)
                    if (hasValidEcuHex(rawCam, "621602")) {
                        val b = extractDataBytes(rawCam, "621602")
                        if (b.size >= 2) {
                            val signedCam = ((b[0] shl 8) or b[1]).toShort().toInt()
                            val deg = round((signedCam / 64.0) * 10.0) / 10.0
                            if (abs(deg) <= 45.0) camErr = abs(deg)
                        }
                    } else if (rawCam.uppercase().contains("7F22") || rawCam.uppercase().contains("NO DATA")) {
                        unsupported.add("221602")
                    }
                }
                if (unsupported.contains("2211A6") && unsupported.contains("221602")) {
                    unsupported.add("7E0_ALL")
                }
            }
        } catch (_: Exception) {
        } finally {
            // MANDATORY: Always restore standard J1979 functional broadcast header (7DF)
            try { sendAtCommand(outs, ins, "ATSH 7DF", 350L) } catch (_: Exception) {}
        }
        return GmEnhancedSnapshot(transF, tccSlip, camErr, krDeg)
    }

    private fun decodeGdiFuelRailPsiOrNull(raw: String): Double? {
        if (!hasValidEcuHex(raw, "4123")) return null
        val b = extractDataBytes(raw, "4123")
        if (b.size < 2) return null
        val kpa = ((b[0] * 256) + b[1]) * 10.0
        return round((kpa * 0.145038) * 10.0) / 10.0
    }


    @SuppressLint("MissingPermission")
    fun startAutonomousWatchdog(serverBaseUrl: String) {
        pingWatchdogNow(serverBaseUrl)
        if (running) return
        running = true
        Log.i(TAG, "Starting Bulletproof OBDLink MX+ Master Diagnostic Watchdog (V19.0)...")
        thread(start = true, isDaemon = true, name = "ObdLinkEdgeMicroAgent") {
            while (running) {
                var socket: BluetoothSocket? = null
                var detectedVin = "1G11Z5SA_IMPALA_36L"
                var minCrankVolts = 14.8
                var maxCoolantF = 70.0
                var maxAbsLtft1 = 0.0
                var maxAbsLtft2 = 0.0
                var samplesCollected = 0
                var startupSnapshotSent = false
                var consecutiveBusErrors = 0
                var lastValidFrame = ""

                try {
                    syncCloudWatchdogStatus(serverBaseUrl)
                    if (cloudWatchdogMode == "off") {
                        for (i in 0 until 4) {
                            Thread.sleep(8000)
                            syncCloudWatchdogStatus(serverBaseUrl)
                            if (cloudWatchdogMode != "off") break
                        }
                        continue
                    }

                    val adapter = BluetoothAdapter.getDefaultAdapter()
                    val obdDevice = adapter?.bondedDevices?.firstOrNull {
                        val n = (it.name ?: "").uppercase()
                        n.contains("OBDLINK") || n.contains("MX+") || n.contains("OBD")
                    }

                    if (obdDevice != null && adapter.isEnabled) {
                        if (adapter.isDiscovering) {
                            try { adapter.cancelDiscovery() } catch (_: Exception) {}
                        }
                        socket = obdDevice.createRfcommSocketToServiceRecord(SPP_UUID)
                        socket.connect()
                        reportedFaultsThisDrive.clear()
                        val ins = socket.inputStream
                        val outs = socket.outputStream

                        sendAtCommand(outs, ins, "ATZ", 1000L)
                        sendAtCommand(outs, ins, "ATE0", 700L)
                        sendAtCommand(outs, ins, "ATL0", 700L)
                        sendAtCommand(outs, ins, "ATH0", 700L)
                        sendAtCommand(outs, ins, "ATS1", 700L)
                        sendAtCommand(outs, ins, "ATSP0", 1200L)

                        val vinHex = sendAtCommand(outs, ins, "0902", 2800L)
                        detectedVin = decodeVinIdentifier(vinHex)
                        val isHemiV8 = detectedVin.contains("RAM_57L")
                        Log.i(TAG, "Connected to OBDLink MX+ | Auto-ID Vehicle: $detectedVin")

                        var cycle = 0
                        var iatF = 75.0
                        var mafGps = 0.0
                        var lastGoodMafGps = 0.0
                        var consecutiveZeroMafTicks = 0
                        var transientMafDropoutsThisDrive = 0
                        var railPsi = 0.0
                        var evapPurgePct = 0.0
                        var transTempF: Double? = null
                        var tccSlipRpm: Double? = null
                        var camErrDeg: Double? = null
                        var knockRetardDeg: Double? = null
                        val unsupportedMode22 = HashSet<String>()
                        val knownDtcsThisDrive = HashSet<String>()
                        val driveStartMs = System.currentTimeMillis()
                        var trimCreepStreak = 0
                        var shudderStreak = 0
                        var camErrStreak = 0
                        var voltDropStreak = 0
                        var prevCruiseRpm = -1.0
                        var prevCruiseMph = -1
                        var prevCruiseTps = -1.0
                        var loadPct = 0.0
                        var tpsPct = 0.0
                        var pcmVolts = 14.2
                        var oilTempF = 195.0
                        var timingAdv = 12.0
                        var baroPsi = 14.5

                        while (running && socket.isConnected && cloudWatchdogMode != "off") {
                            syncCloudWatchdogStatus(serverBaseUrl)
                            if (cloudWatchdogMode == "off") {
                                Log.i(TAG, "Boss switched Watchdog to OFF — closing Bluetooth socket immediately to free OBDLink app.")
                                break
                            }

                            val voltsStr = sendAtCommand(outs, ins, "ATRV", 900L)
                            val voltsClean = voltsStr.filter { it.isDigit() || it == '.' }
                            val volts = voltsClean.toDoubleOrNull() ?: 14.2
                            if (volts in 7.0..15.8) minCrankVolts = min(minCrankVolts, volts)

                            val rpmRaw = sendAtCommand(outs, ins, "010C", 1300L)
                            val coolantRaw = sendAtCommand(outs, ins, "0105", 1100L)
                            val ecuResponding = hasValidEcuHex(rpmRaw, "410C") || hasValidEcuHex(coolantRaw, "4105")

                            if (!ecuResponding && pendingServerCmd.isEmpty() && startupSnapshotSent) {
                                consecutiveBusErrors++
                                if (consecutiveBusErrors >= 4 && cloudWatchdogMode != "live") {
                                    Log.i(TAG, "ECU CAN bus asleep/ignition off for 4 cycles; releasing socket.")
                                    break
                                }
                            } else {
                                consecutiveBusErrors = 0
                            }

                            val rpm = decodeRpmOrNull(rpmRaw) ?: 0.0
                            val coolantF = if (hasValidEcuHex(coolantRaw, "4105")) decodeCoolantF(coolantRaw) else 75.0
                            val speedMph = ((decodeSingleByteOrNull(sendAtCommand(outs, ins, "010D", 900L), "410D") ?: 0) * 0.621371).toInt()
                            val stft1 = decodeFuelTrimPct(sendAtCommand(outs, ins, "0106", 900L), "4106")
                            val ltft1 = decodeFuelTrimPct(sendAtCommand(outs, ins, "0107", 900L), "4107")
                            val stft2 = decodeFuelTrimPct(sendAtCommand(outs, ins, "0108", 900L), "4108")
                            val ltft2 = decodeFuelTrimPct(sendAtCommand(outs, ins, "0109", 900L), "4109")
                            val mapKpa = decodeSingleByteOrNull(sendAtCommand(outs, ins, "010B", 900L), "410B") ?: 0

                            if (cycle % 2 == 0) {
                                loadPct = decodePctByte(sendAtCommand(outs, ins, "0104", 900L), "4104")
                                iatF = decodeTempF(sendAtCommand(outs, ins, "010F", 900L), "410F")
                                val rawMafResp = sendAtCommand(outs, ins, "0110", 900L)
                                val parsedMaf = decodeMafGpsOrNull(rawMafResp)
                                if (parsedMaf == null) {
                                    mafGps = lastGoodMafGps
                                } else if (parsedMaf <= 0.05 && rpm >= 500.0 && mapKpa >= 20) {
                                    consecutiveZeroMafTicks++
                                    if (consecutiveZeroMafTicks < 3) {
                                        transientMafDropoutsThisDrive++
                                        mafGps = if (lastGoodMafGps > 0.5) lastGoodMafGps else 3.5
                                    } else {
                                        mafGps = 0.0
                                        if (reportedFaultsThisDrive.add("SUSTAINED_MAF_DROPOUT")) {
                                            reportToThor(
                                                serverBaseUrl,
                                                detectedVin,
                                                "DTC Alert: Sustained MAF Sensor Dropout (0.0 g/s for $consecutiveZeroMafTicks consecutive reads at ${rpm.toInt()} RPM)",
                                                lastValidFrame,
                                                saveToDb = true
                                            )
                                        }
                                    }
                                } else {
                                    consecutiveZeroMafTicks = 0
                                    mafGps = parsedMaf
                                    if (parsedMaf > 0.2) lastGoodMafGps = parsedMaf
                                }
                                tpsPct = decodePctByte(sendAtCommand(outs, ins, "0111", 900L), "4111")
                                val vMod = decodeControlModuleVolts(sendAtCommand(outs, ins, "0142", 900L))
                                if (vMod > 5.0) pcmVolts = vMod
                                timingAdv = decodeTimingAdvance(sendAtCommand(outs, ins, "010E", 900L))
                                val oilRaw = sendAtCommand(outs, ins, "015C", 900L)
                                oilTempF = if (hasValidEcuHex(oilRaw, "415C")) decodeTempF(oilRaw, "415C") else coolantF
                                    val baroKpa = decodeSingleByteOrNull(sendAtCommand(outs, ins, "0133", 900L), "4133")
                                    if (baroKpa != null && baroKpa > 50) baroPsi = round((baroKpa * 0.145038) * 10.0) / 10.0
                                    decodeGdiFuelRailPsiOrNull(sendAtCommand(outs, ins, "0123", 750L))?.let { railPsi = it }
                                    evapPurgePct = decodePctByte(sendAtCommand(outs, ins, "012E", 750L), "412E")
                                }
                                if (!isHemiV8 && cycle % 6 == 0 && rpm > 0.0) {
                                    val enh = pollGmEnhancedMode22Safe(outs, ins, unsupportedMode22)
                                    if (enh.transTempF != null) transTempF = enh.transTempF
                                    if (enh.tccSlipRpm != null) tccSlipRpm = enh.tccSlipRpm
                                    if (enh.camErrDeg != null) camErrDeg = enh.camErrDeg
                                    if (enh.knockRetardDeg != null) knockRetardDeg = enh.knockRetardDeg
                                }

                            maxCoolantF = max(maxCoolantF, coolantF)
                            maxAbsLtft1 = max(maxAbsLtft1, abs(ltft1))
                            maxAbsLtft2 = max(maxAbsLtft2, abs(ltft2))
                            samplesCollected++

                            val frame = "RPM: ${rpm.toInt()} | Speed: ${speedMph}MPH | Coolant: ${coolantF.toInt()}F | Oil: ${oilTempF.toInt()}F | IAT: ${iatF.toInt()}F" +
                                " | Batt: ${volts}V (PCM: ${pcmVolts}V, CrankMin: ${minCrankVolts}V)" +
                                " | B1 Trim: ST ${stft1}% / LT ${ltft1}% | B2 Trim: ST ${stft2}% / LT ${ltft2}%" +
                                " | MAF: ${mafGps}g/s (Glitches:${transientMafDropoutsThisDrive}) | MAP: ${mapKpa}kPa | Baro: ${baroPsi}psi | Load: ${loadPct.toInt()}% | TPS: ${tpsPct.toInt()}% | Timing: ${timingAdv}deg" +
                                (if (railPsi > 20.0) " | Rail: ${railPsi.toInt()}PSI" else "") +
                                " | EVAP: ${evapPurgePct.toInt()}%" +
                                (if (transTempF != null) " | Trans: ${transTempF!!.toInt()}F" else "") +
                                (if (tccSlipRpm != null) " | TCCSlip: ${tccSlipRpm}RPM" else "") +
                                (if (camErrDeg != null) " | CamErr: ${camErrDeg}deg" else "") +
                                (if (knockRetardDeg != null) " | KR: ${knockRetardDeg}deg" else "")
                            lastValidFrame = frame

                            synchronized(ringBuffer) {
                                if (ringBuffer.size >= 300) ringBuffer.removeFirst()
                                ringBuffer.addLast(frame)
                            }

                            if (pendingServerCmd == "CLEAR_DTCS") {
                                pendingServerCmd = ""
                                Log.i(TAG, "Executing Mode 04 Clear DTCs & Reset CEL on vehicle ECU...")
                                val m04Resp = sendAtCommand(outs, ins, "04", 2500L)
                                Thread.sleep(500)
                                executeFullDtcAndMode06Scan(
                                    serverBaseUrl, outs, ins, detectedVin, isHemiV8, frame,
                                    "Mode 04 Clear Executed ($m04Resp)"
                                )
                            } else if (pendingServerCmd == "FULL_SCAN") {
                                pendingServerCmd = ""
                                Log.i(TAG, "Executing Boss On-Demand Full Mode 03/07/0A/06 Diagnostic Scan...")
                                executeFullDtcAndMode06Scan(
                                    serverBaseUrl, outs, ins, detectedVin, isHemiV8, frame,
                                    "On-Demand Full Scan"
                                )
                            } else if (pendingServerCmd == "ROAD_TEST_30S") {
                                pendingServerCmd = ""
                                execute30SecRoadTestFlightRecorder(
                                    serverBaseUrl, outs, ins, detectedVin, isHemiV8, unsupportedMode22
                                )
                            }

                            if (!startupSnapshotSent) {
                                startupSnapshotSent = true
                                executeFullDtcAndMode06Scan(
                                    serverBaseUrl, outs, ins, detectedVin, isHemiV8, frame,
                                    "Initial Connect Scan"
                                )
                            } else if (cloudWatchdogMode == "live" || (rpm > 0.0 && cycle % 4 == 0) || samplesCollected <= 2) {
                                reportToThor(
                                    serverBaseUrl,
                                    detectedVin,
                                    "LIVE_STREAM_TICK",
                                    frame,
                                    saveToDb = false
                                )
                            }

                            // === WO-150 2-TIER PRE-CODE EARLY WARNING & INSTANT MID-DRIVE DTC WATCHDOG ===
                            val coolLimit = if (speedMph >= 50) 226.0 else 235.0
                            if (coolantF >= coolLimit && reportedFaultsThisDrive.add("HIGH_COOLANT")) {
                                reportToThor(serverBaseUrl, detectedVin, "Pre-Code Alert: Speed-Aware Thermal Drift (${coolantF.toInt()}F at $speedMph MPH)", frame, saveToDb = true)
                            }
                            val driveMinutes = (System.currentTimeMillis() - driveStartMs) / 60_000.0
                            if (driveMinutes >= 12.0 && speedMph >= 35 && coolantF in 80.0..173.0 && reportedFaultsThisDrive.add("STUCK_OPEN_THERMOSTAT")) {
                                reportToThor(serverBaseUrl, detectedVin, "Pre-Code Early Warning: Slow Warmup / Possible Stuck-Open Thermostat (${coolantF.toInt()}F after ${driveMinutes.toInt()}m driving)", frame, saveToDb = true)
                            }

                            // Debounced Pre-Code Fuel Trim Creep (catches vacuum/MAF/injector/purge drift well before GM's +23% P0171/P0174 CEL!)
                            val totalTrimB1 = stft1 + ltft1
                            val totalTrimB2 = stft2 + ltft2
                            if (consecutiveZeroMafTicks == 0 && rpm >= 550.0 && (abs(ltft1) >= 8.5 || abs(ltft2) >= 8.5 || abs(totalTrimB1) >= 14.5 || abs(totalTrimB2) >= 14.5)) {
                                trimCreepStreak++
                                if (trimCreepStreak >= 4 && reportedFaultsThisDrive.add("PRE_CODE_FUEL_TRIM")) {
                                    reportToThor(serverBaseUrl, detectedVin, "Pre-Code Early Warning: Sustained Fuel Trim Drift (B1 LT:${ltft1}%/ST:${stft1}%, B2 LT:${ltft2}%/ST:${stft2}%)", frame, saveToDb = true)
                                }
                            } else {
                                trimCreepStreak = 0
                            }
                            if (consecutiveZeroMafTicks == 0 && abs(ltft1 - ltft2) >= 7.5 && reportedFaultsThisDrive.add("BANK_IMBALANCE")) {
                                reportToThor(serverBaseUrl, detectedVin, "Pre-Code Early Warning: Bank 1 vs Bank 2 Fuel Trim Imbalance (B1: $ltft1% vs B2: $ltft2%)", frame, saveToDb = true)
                            }

                            // Pre-Code Loose-MAF Connector Contact Bounce Trend (3+ transient 0.0g/s glitches in one drive)
                            if (transientMafDropoutsThisDrive >= 3 && reportedFaultsThisDrive.add("PRE_CODE_MAF_GLITCH_TREND")) {
                                reportToThor(serverBaseUrl, detectedVin, "Pre-Code Early Warning: Intermittent MAF Connector Dropout (${transientMafDropoutsThisDrive} brief 0.0g/s bounces under vibration)", frame, saveToDb = true)
                            }

                            // Pre-Code 6T70 Torque Converter Clutch (TCC) Shudder / Slip & Trans Temp Watch
                            if (transTempF != null && transTempF!! >= 222.0 && reportedFaultsThisDrive.add("PRE_CODE_TRANS_TEMP")) {
                                reportToThor(serverBaseUrl, detectedVin, "Pre-Code Early Warning: Elevated Transmission Fluid Temp (${transTempF!!.toInt()}F)", frame, saveToDb = true)
                            }
                            val steadyLightCruise = (speedMph in 35..68 && prevCruiseMph > 0 && abs(speedMph - prevCruiseMph) <= 1 && prevCruiseTps >= 0.0 && abs(tpsPct - prevCruiseTps) <= 2.2 && loadPct in 24.0..72.0)
                            val rpmOscillation = if (steadyLightCruise && prevCruiseRpm > 0.0) abs(rpm - prevCruiseRpm) else 0.0
                            val slipAnomaly = (tccSlipRpm != null && steadyLightCruise && abs(tccSlipRpm!!) in 30.0..180.0) || (steadyLightCruise && rpmOscillation in 38.0..135.0)
                            if (slipAnomaly) {
                                shudderStreak++
                                if (shudderStreak >= 3 && reportedFaultsThisDrive.add("PRE_CODE_TCC_SHUDDER")) {
                                    reportToThor(serverBaseUrl, detectedVin, "Pre-Code Early Warning: Cruise Torque-Converter Slip / Shudder Signature (RPMDelta:${rpmOscillation.toInt()}, TCCSlip:${tccSlipRpm ?: "N/A"} at ${speedMph}MPH)", frame, saveToDb = true)
                                }
                            } else if (!steadyLightCruise) {
                                shudderStreak = max(0, shudderStreak - 1)
                            }
                            prevCruiseRpm = rpm
                            prevCruiseMph = speedMph
                            prevCruiseTps = tpsPct

                            // Pre-Code Cam Phaser / Timing Chain Correlation Drift
                            if (camErrDeg != null && camErrDeg!! >= 4.0) {
                                camErrStreak++
                                if (camErrStreak >= 3 && reportedFaultsThisDrive.add("PRE_CODE_CAM_DRIFT")) {
                                    reportToThor(serverBaseUrl, detectedVin, "Pre-Code Early Warning: Camshaft Phaser / Timing Correlation Drift (${camErrDeg}deg)", frame, saveToDb = true)
                                }
                            } else {
                                camErrStreak = 0
                            }

                            // Pre-Code Battery Cranking Sag, Charging & Harness Ground-Drop Check
                            if (minCrankVolts in 7.2..10.05 && reportedFaultsThisDrive.add("PRE_CODE_WEAK_CRANK_CCA")) {
                                reportToThor(serverBaseUrl, detectedVin, "Pre-Code Early Warning: Weak Starter Cranking Voltage Dip (${minCrankVolts}V - Battery CCA Aging)", frame, saveToDb = true)
                            }
                            if (rpm >= 600.0 && (volts < 12.6 || volts > 15.25) && reportedFaultsThisDrive.add("CHARGING_VOLTS")) {
                                reportToThor(serverBaseUrl, detectedVin, "DTC Alert: Charging System Voltage Anomaly (Pin16: ${volts}V, PCM: ${pcmVolts}V)", frame, saveToDb = true)
                            }
                            if (rpm >= 600.0 && pcmVolts > 8.0 && abs(volts - pcmVolts) >= 0.60) {
                                voltDropStreak++
                                if (voltDropStreak >= 3 && reportedFaultsThisDrive.add("PRE_CODE_GROUND_DROP")) {
                                    reportToThor(serverBaseUrl, detectedVin, "Pre-Code Early Warning: Chassis/Harness Voltage Drop (OBD Pin16 ${volts}V vs PCM ${pcmVolts}V)", frame, saveToDb = true)
                                }
                            } else {
                                voltDropStreak = 0
                            }

                            // Fast Mid-Drive Pending-DTC (Mode $07) + Stored (0101/03) + Sub-Threshold Mode $06 Misfire Sweep (~every 18-20s)
                            if (cycle > 0 && cycle % 15 == 0 && rpm > 0.0) {
                                val m0101 = sendAtCommand(outs, ins, "0101", 900L)
                                val b01 = extractDataBytes(m0101, "4101")
                                val milOn = b01.isNotEmpty() && ((b01[0] and 0x80) != 0)
                                val dtcCnt = if (b01.isNotEmpty()) (b01[0] and 0x7F) else 0
                                val pending07 = decodeDtcList(sendAtCommand(outs, ins, "07", 1500L), "47")
                                val newPending = pending07.filter { knownDtcsThisDrive.add("P07_$it") }

                                if (milOn || dtcCnt > 0 || newPending.isNotEmpty()) {
                                    val m03 = decodeDtcList(sendAtCommand(outs, ins, "03", 1600L), "43")
                                    val newStored = m03.filter { knownDtcsThisDrive.add("S03_$it") }
                                    if (newStored.isNotEmpty() || newPending.isNotEmpty()) {
                                        executeFullDtcAndMode06Scan(
                                            serverBaseUrl, outs, ins, detectedVin, isHemiV8, frame,
                                            "Mid-Drive ECU Code Trigger (Stored:${if (m03.isEmpty()) "None" else m03.joinToString(",")} | PendingFirstStrike:${if (pending07.isEmpty()) "None" else pending07.joinToString(",")})"
                                        )
                                    }
                                } else if (cycle % 30 == 0) {
                                    // Check Mode $06 TID $0B for 3+ pre-DTC cylinder misfires even when 0 codes are stored/pending!
                                    val maxCyl = if (isHemiV8) 8 else 6
                                    val preDtcMisfires = ArrayList<String>()
                                    for (cyl in 1..maxCyl) {
                                        val midHex = "A" + (cyl + 1)
                                        val cnt = extractMode06MisfireCount(sendAtCommand(outs, ins, "06$midHex", 1000L), midHex)
                                        if (cnt >= 3) preDtcMisfires.add("Cyl$cyl:$cnt")
                                    }
                                    if (preDtcMisfires.isNotEmpty() && reportedFaultsThisDrive.add("PRE_CODE_MISFIRE_${preDtcMisfires.joinToString("_")}")) {
                                        reportToThor(
                                            serverBaseUrl,
                                            detectedVin,
                                            "Pre-Code Early Warning: Sub-Threshold Mode 06 Cylinder Misfires (${preDtcMisfires.joinToString(", ")}) with 0 CEL",
                                            frame,
                                            saveToDb = true
                                        )
                                    }
                                }
                            }

                            cycle++
                            Thread.sleep(if (cloudWatchdogMode == "live") 500L else if (rpm > 0.0) 1200L else 12000L)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "OBD Watchdog cycle note: ${e.message}")
                } finally {
                    try { socket?.close() } catch (_: Exception) {}
                }

                val waitCycles = if (cloudWatchdogMode == "live") 1 else 6
                for (w in 0 until waitCycles) {
                    Thread.sleep(8000)
                    syncCloudWatchdogStatus(serverBaseUrl)
                    if (cloudWatchdogMode == "live" || pendingServerCmd.isNotEmpty()) break
                }
            }
        }
    }

    private fun hasValidEcuHex(raw: String, prefix: String): Boolean {
        val u = raw.uppercase()
        if (u.isEmpty() || u.contains("NO DATA") || u.contains("UNABLE") || u.contains("ERROR") || u.contains("STOPPED") || u.contains("?")) return false
        return extractDataBytes(raw, prefix).isNotEmpty()
    }

    private fun decodeVinIdentifier(rawHex: String): String {
        val clean = stripIsoTpFraming(rawHex)
        if (clean.contains("314336") || clean.contains("334336") || clean.contains("314437")) {
            return "1C6RR7LT_RAM_57L"
        }
        return "1G11Z5SA_IMPALA_36L"
    }

    private fun stripIsoTpFraming(raw: String): String {
        val lines = raw.uppercase().replace(CR_STR, LF_STR).split(LF_STR)
        val sb = StringBuilder()
        for (line in lines) {
            var ln = line.trim()
            if (ln.isEmpty() || ln.contains("SEARCHING") || ln.contains("NO DATA") || ln.contains("STOPPED") || ln.contains("UNABLE")) continue
            val colonIdx = ln.indexOf(':')
            if (colonIdx in 1..2) {
                ln = ln.substring(colonIdx + 1).trim()
            }
            if (ln.length == 3 && ln.all { it.isDigit() || it in 'A'..'F' }) continue
            for (ch in ln) {
                if (ch.isDigit() || ch in 'A'..'F') {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    private fun extractDataBytes(raw: String, headerPrefix: String): List<Int> {
        val hexOnly = stripIsoTpFraming(raw)
        val pref = headerPrefix.uppercase().replace(" ", "")
        val idx = hexOnly.indexOf(pref)
        if (idx < 0 || idx + pref.length + 2 > hexOnly.length) return emptyList()
        val tail = hexOnly.substring(idx + pref.length)
        val out = ArrayList<Int>()
        var i = 0
        while (i + 1 < tail.length) {
            val b = tail.substring(i, i + 2).toIntOrNull(16)
            if (b != null) out.add(b)
            i += 2
        }
        return out
    }

    private fun decodeRpmOrNull(raw: String): Double? {
        if (!hasValidEcuHex(raw, "410C")) return null
        val b = extractDataBytes(raw, "410C")
        return if (b.size >= 2) ((b[0] * 256) + b[1]) / 4.0 else null
    }

    private fun decodeCoolantF(raw: String): Double = decodeTempF(raw, "4105")

    private fun decodeTempF(raw: String, prefix: String): Double {
        val b = extractDataBytes(raw, prefix)
        val c = if (b.isNotEmpty()) (b[0] - 40).toDouble() else 25.0
        return round(((c * 9.0 / 5.0) + 32.0) * 10.0) / 10.0
    }

    private fun decodeFuelTrimPct(raw: String, prefix: String): Double {
        val b = extractDataBytes(raw, prefix)
        return if (b.isNotEmpty()) round(((b[0] - 128) * 100.0 / 128.0) * 10.0) / 10.0 else 0.0
    }

    private fun decodePctByte(raw: String, prefix: String): Double {
        val b = extractDataBytes(raw, prefix)
        return if (b.isNotEmpty()) round((b[0] * 100.0 / 255.0) * 10.0) / 10.0 else 0.0
    }

    private fun decodeMafGpsOrNull(raw: String): Double? {
        if (!hasValidEcuHex(raw, "4110")) return null
        val b = extractDataBytes(raw, "4110")
        return if (b.size >= 2) round((((b[0] * 256) + b[1]) / 100.0) * 10.0) / 10.0 else null
    }

    private fun decodeControlModuleVolts(raw: String): Double {
        val b = extractDataBytes(raw, "4142")
        return if (b.size >= 2) round((((b[0] * 256) + b[1]) / 1000.0) * 100.0) / 100.0 else 0.0
    }

    private fun decodeTimingAdvance(raw: String): Double {
        val b = extractDataBytes(raw, "410E")
        return if (b.isNotEmpty()) round(((b[0] / 2.0) - 64.0) * 10.0) / 10.0 else 12.0
    }

    private fun decodeSingleByteOrNull(raw: String, prefix: String): Int? {
        val b = extractDataBytes(raw, prefix)
        return if (b.isNotEmpty()) b[0] else null
    }

    private fun sendAtCommand(outs: OutputStream, ins: InputStream, cmd: String, timeoutMs: Long = 1500L): String {
        try {
            while (ins.available() > 0) {
                val discard = ByteArray(128)
                if (ins.read(discard) <= 0) break
            }
        } catch (_: Exception) {}

        outs.write((cmd + CR_STR).toByteArray())
        outs.flush()
        val sb = StringBuilder()
        val buf = ByteArray(256)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ins.available() > 0) {
                val n = ins.read(buf)
                if (n > 0) {
                    val chunk = String(buf, 0, n)
                    sb.append(chunk)
                    if (chunk.contains(">")) break
                }
            } else {
                Thread.sleep(16)
            }
        }
        return sb.toString().replace(">", "").trim()
    }

    fun decodeDtcList(raw: String, modeHex: String): List<String> {
        val u = raw.uppercase()
        if (u.isEmpty() || u.contains("NO DATA") || u.contains("UNABLE") || u.contains("ERROR")) return emptyList()
        val mHex = modeHex.uppercase()
        val result = ArrayList<String>()

        val rawLines = u.replace(CR_STR, LF_STR).split(LF_STR)
        val ecuPayloads = ArrayList<String>()
        var currentMulti = StringBuilder()

        for (rawLn in rawLines) {
            val ln = rawLn.trim()
            if (ln.isEmpty() || ln.contains("SEARCHING") || ln.contains("STOPPED")) continue
            if (ln.length == 3 && ln.all { it.isDigit() || it in 'A'..'F' }) {
                if (currentMulti.isNotEmpty()) {
                    ecuPayloads.add(currentMulti.toString())
                    currentMulti = StringBuilder()
                }
                continue
            }
            val colonIdx = ln.indexOf(':')
            if (colonIdx in 1..2) {
                val seq = ln.substring(0, colonIdx).trim()
                val hexPart = ln.substring(colonIdx + 1).filter { it.isDigit() || it in 'A'..'F' }
                if (seq == "0" && currentMulti.isNotEmpty()) {
                    ecuPayloads.add(currentMulti.toString())
                    currentMulti = StringBuilder()
                }
                currentMulti.append(hexPart)
            } else {
                if (currentMulti.isNotEmpty()) {
                    ecuPayloads.add(currentMulti.toString())
                    currentMulti = StringBuilder()
                }
                val hexPart = ln.filter { it.isDigit() || it in 'A'..'F' }
                if (hexPart.isNotEmpty()) ecuPayloads.add(hexPart)
            }
        }
        if (currentMulti.isNotEmpty()) ecuPayloads.add(currentMulti.toString())

        for (ecuHex in ecuPayloads) {
            val idx = ecuHex.indexOf(mHex)
            if (idx < 0 || idx + 4 > ecuHex.length) continue
            val afterMode = ecuHex.substring(idx + 2)
            val bytes = ArrayList<Int>()
            var p = 0
            while (p + 1 < afterMode.length) {
                afterMode.substring(p, p + 2).toIntOrNull(16)?.let { bytes.add(it) }
                p += 2
            }
            if (bytes.isEmpty() || bytes[0] == 0) continue
            val expectedCount = bytes[0]
            val useCountByte = (bytes.size >= 1 + expectedCount * 2) || (bytes.size % 2 == 1)
            var i = if (useCountByte) 1 else 0
            var parsedForEcu = 0
            while (i + 1 < bytes.size) {
                if (useCountByte && parsedForEcu >= expectedCount) break
                val b1 = bytes[i]
                val b2 = bytes[i + 1]
                if (b1 != 0 || b2 != 0) {
                    val sysChar = when ((b1 shr 6) and 0x03) {
                        0 -> 'P'
                        1 -> 'C'
                        2 -> 'B'
                        else -> 'U'
                    }
                    val d1 = ((b1 shr 4) and 0x03).toString(16).uppercase()
                    val d2 = (b1 and 0x0F).toString(16).uppercase()
                    val d3 = ((b2 shr 4) and 0x0F).toString(16).uppercase()
                    val d4 = (b2 and 0x0F).toString(16).uppercase()
                    val code = "$sysChar$d1$d2$d3$d4"
                    if (code != "P0000" && !result.contains(code)) {
                        result.add(code)
                    }
                    parsedForEcu++
                }
                i += 2
            }
        }
        return result
    }

    private fun formatDtcBundle(m03: String, m07: String, m0A: String): String {
        val c03 = decodeDtcList(m03, "43")
        val c07 = decodeDtcList(m07, "47")
        val c0A = decodeDtcList(m0A, "4A")
        if (c03.isEmpty() && c07.isEmpty() && c0A.isEmpty()) return "0 Stored / 0 Pending / 0 Permanent DTCs"
        return "Stored(03): " + (if (c03.isEmpty()) "None" else c03.joinToString(", ")) +
            " | Pending(07): " + (if (c07.isEmpty()) "None" else c07.joinToString(", ")) +
            " | Perm(0A): " + (if (c0A.isEmpty()) "None" else c0A.joinToString(", "))
    }

    fun extractMode06MisfireCount(raw: String, midHex: String): Int {
        // ISO-15031-5 CAN Mode $06: Byte0=46, Byte1=MID, Byte2=TID(0B=Latest, 0C=Avg), Byte3=UASID(0x24), Byte4..5=Value
        val hexClean = stripIsoTpFraming(raw)
        val mUp = midHex.uppercase()
        val marker = "46$mUp"
        var searchFrom = 0
        var latestDriveCount = 0
        while (true) {
            val idx = hexClean.indexOf(marker, searchFrom)
            if (idx < 0 || idx + 12 > hexClean.length) break
            val tid = hexClean.substring(idx + 4, idx + 6).toIntOrNull(16) ?: -1
            val vHi = hexClean.substring(idx + 8, idx + 10).toIntOrNull(16) ?: 0
            val vLo = hexClean.substring(idx + 10, idx + 12).toIntOrNull(16) ?: 0
            val value = (vHi * 256) + vLo
            if (tid == 0x0B && value in 1..65000) {
                return value
            }
            if (tid == 0x0C && value in 1..65000 && latestDriveCount == 0) {
                latestDriveCount = value
            }
            searchFrom = idx + 4
        }
        return latestDriveCount
    }

    private fun reportToThor(
        serverBaseUrl: String,
        vinId: String,
        faultTrigger: String,
        freezeFrame: String,
        saveToDb: Boolean = true
    ) {
        try {
            val cleanBase = serverBaseUrl.trimEnd('/')
            val qVin = URLEncoder.encode(vinId, "UTF-8")
            val qTrig = URLEncoder.encode(faultTrigger, "UTF-8")
            val qFrame = URLEncoder.encode(freezeFrame, "UTF-8")
            val saveFlag = if (saveToDb) "1" else "0"
            val req = Request.Builder()
                .url("$cleanBase/api/obd-telemetry?vin=$qVin&fault_trigger=$qTrig&live_freeze_frame=$qFrame&save=$saveFlag")
                .header("User-Agent", "ThorObdWatchdog/18.3 (Samsung-Note20Ultra)")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                val bodyTxt = resp.body?.string() ?: ""
                parseServerStateJson(bodyTxt)
                Log.i(TAG, "OBD Cloud Telemetry -> HTTP ${resp.code} ($faultTrigger)")
            }
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun pingWatchdogNow(serverBaseUrl: String) {
        thread(start = true, isDaemon = true, name = "ObdWatchdogPing") {
            try {
                syncCloudWatchdogStatus(serverBaseUrl)
                val adapter = BluetoothAdapter.getDefaultAdapter()
                val btOn = adapter?.isEnabled == true
                val obdDev = adapter?.bondedDevices?.firstOrNull {
                    val n = (it.name ?: "").uppercase()
                    n.contains("OBDLINK") || n.contains("MX+") || n.contains("OBD")
                }
                val pairedName = obdDev?.name ?: "NOT_PAIRED"
                val statusFrame = "PhoneWatchdog: V19.0 ACTIVE | Bluetooth: ${if (btOn) "ON" else "OFF"} | PairedAdapter: $pairedName | Mode: ${cloudWatchdogMode.uppercase()}"
                reportToThor(serverBaseUrl, "PHONE_OBD_WATCHDOG_PING", "On-Demand Phone Watchdog Status Ping", statusFrame, saveToDb = false)
            } catch (e: Exception) {
                reportToThor(serverBaseUrl, "PHONE_OBD_WATCHDOG_PING", "On-Demand Phone Watchdog Status Ping", "Ping note: ${e.message}", saveToDb = false)
            }
        }
    }
}
