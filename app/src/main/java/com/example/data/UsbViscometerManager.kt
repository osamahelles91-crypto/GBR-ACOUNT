package com.example.data

import android.content.Context
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDevice
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.util.Locale
import kotlin.random.Random

data class ParsedViscosityData(
    val spindle: String,
    val speedRpm: String,
    val viscosityCp: Double,
    val torquePct: Double,
    val temperature: String,
    val rawString: String
)

class UsbViscometerManager private constructor() {

    companion object {
        private const val TAG = "UsbViscometerManager"
        @Volatile
        private var instance: UsbViscometerManager? = null

        fun getInstance(): UsbViscometerManager {
            return instance ?: synchronized(this) {
                instance ?: UsbViscometerManager().also { instance = it }
            }
        }
    }

    private val _connectionStatus = MutableStateFlow("disconnected") // "disconnected", "scanning", "connected", "simulation"
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _liveDataStream = MutableSharedFlow<String>(replay = 1)
    val liveDataStream: SharedFlow<String> = _liveDataStream.asSharedFlow()

    private val _parsedReading = MutableStateFlow<ParsedViscosityData?>(null)
    val parsedReading: StateFlow<ParsedViscosityData?> = _parsedReading.asStateFlow()

    private var usbSerialPort: UsbSerialPort? = null
    private var readJob: Job? = null
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var isSimulating = false
    private var simulationJob: Job? = null

    // For keeping track of active timer countdowns
    private val _timerCountdown = MutableStateFlow(0)
    val timerCountdown: StateFlow<Int> = _timerCountdown.asStateFlow()

    private val _isTimerRunning = MutableStateFlow(false)
    val isTimerRunning: StateFlow<Boolean> = _isTimerRunning.asStateFlow()

    private var timerJob: Job? = null

    fun toggleSimulation(enable: Boolean) {
        isSimulating = enable
        if (enable) {
            disconnectDevice()
            _connectionStatus.value = "simulation"
            _deviceName.value = "NDJ-8S (محاكي)"
            startSimulationStream()
        } else {
            stopSimulationStream()
            _connectionStatus.value = "disconnected"
            _deviceName.value = ""
        }
    }

    private fun startSimulationStream() {
        simulationJob?.cancel()
        simulationJob = managerScope.launch {
            while (isActive) {
                delay(1500) // Emit reading every 1.5 seconds
                // Generate simulated data in the exact expected format
                val spindle = (1..4).random().toString()
                val speeds = listOf("0.3", "0.6", "1.5", "3.0", "6.0", "12.0", "30.0", "60.0")
                val speed = speeds.random()
                val viscosity = Random.nextDouble(100.0, 5000.0)
                val torque = Random.nextDouble(15.0, 85.0)
                
                // e.g., "3#30 RPM   645 16.1  -.-"
                val rawString = String.format(
                    Locale.US,
                    "%s#%s RPM   %.0f %.1f  -.-",
                    spindle, speed, viscosity, torque
                )
                
                _liveDataStream.emit(rawString)
                val parsed = parseRawData(rawString)
                if (parsed != null) {
                    _parsedReading.value = parsed
                }
            }
        }
    }

    private fun stopSimulationStream() {
        simulationJob?.cancel()
        simulationJob = null
        _parsedReading.value = null
    }

    fun startTimer(durationSeconds: Int = 30, onFinished: (ParsedViscosityData) -> Unit) {
        timerJob?.cancel()
        _timerCountdown.value = durationSeconds
        _isTimerRunning.value = true
        timerJob = CoroutineScope(Dispatchers.Main).launch {
            while (_timerCountdown.value > 0) {
                delay(1000)
                _timerCountdown.value -= 1
            }
            _isTimerRunning.value = false
            // At completion, grab the latest reading
            _parsedReading.value?.let {
                onFinished(it)
            }
        }
    }

    fun cancelTimer() {
        timerJob?.cancel()
        _isTimerRunning.value = false
        _timerCountdown.value = 0
    }

    fun connectDevice(context: Context): Boolean {
        if (isSimulating) {
            return true
        }

        disconnectDevice()
        _connectionStatus.value = "scanning"

        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbManager == null) {
            _connectionStatus.value = "disconnected"
            Log.e(TAG, "USB Manager is null")
            return false
        }

        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            _connectionStatus.value = "disconnected"
            Log.w(TAG, "No USB drivers found")
            return false
        }

        // Find FTDI driver
        val driver = availableDrivers.firstOrNull { d ->
            val desc = d.device.deviceName ?: ""
            desc.contains("FTDI", ignoreCase = true) || d.device.vendorId == 0x0403 // FTDI Vendor ID
        } ?: availableDrivers.first() // Fallback to first available

        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            _connectionStatus.value = "disconnected"
            Log.e(TAG, "Failed to open USB connection - Permission required?")
            return false
        }

        val port = driver.ports[0]
        try {
            port.open(connection)
            // 9600 Baud, 8 Data bits, 1 Stop bit, Parity None
            port.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            usbSerialPort = port
            _connectionStatus.value = "connected"
            _deviceName.value = "NDJ-8S (FTDI FT231X)"
            
            startReadingStream()
            return true
        } catch (e: IOException) {
            Log.e(TAG, "Error configuring USB serial port", e)
            _connectionStatus.value = "disconnected"
            _deviceName.value = ""
            try {
                port.close()
            } catch (ignored: Exception) {}
            return false
        }
    }

    private fun startReadingStream() {
        readJob?.cancel()
        readJob = managerScope.launch {
            val buffer = ByteArray(1024)
            val lineBuffer = StringBuilder()
            
            while (isActive) {
                val port = usbSerialPort
                if (port == null) {
                    delay(1000)
                    continue
                }

                try {
                    val numBytes = port.read(buffer, 200)
                    if (numBytes > 0) {
                        val str = String(buffer, 0, numBytes, Charsets.US_ASCII)
                        lineBuffer.append(str)
                        
                        // Process complete lines (usually ending with \n or \r)
                        var index: Int
                        while (lineBuffer.indexOf("\n").also { index = it } != -1) {
                            val line = lineBuffer.substring(0, index).trim()
                            lineBuffer.delete(0, index + 1)
                            
                            if (line.isNotEmpty()) {
                                _liveDataStream.emit(line)
                                val parsed = parseRawData(line)
                                if (parsed != null) {
                                    _parsedReading.value = parsed
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Read error, disconnecting", e)
                    withContext(Dispatchers.Main) {
                        disconnectDevice()
                    }
                    break
                }
                delay(100)
            }
        }
    }

    fun disconnectDevice() {
        readJob?.cancel()
        readJob = null
        cancelTimer()
        try {
            usbSerialPort?.close()
        } catch (ignored: Exception) {}
        usbSerialPort = null
        if (!isSimulating) {
            _connectionStatus.value = "disconnected"
            _deviceName.value = ""
            _parsedReading.value = null
        }
    }

    fun parseRawData(raw: String): ParsedViscosityData? {
        try {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            // Expected format: "3#30 RPM   645 16.1  -.-"
            // Let's match with a highly robust regex:
            val regex = Regex("""(\d+)#([\d.]+)\s*RPM\s+([\d.]+)\s+([\d.-]+)\s+(\S+)""")
            val matchResult = regex.find(trimmed)
            if (matchResult != null) {
                val (spindle, speed, viscosity, torque, temp) = matchResult.destructured
                return ParsedViscosityData(
                    spindle = spindle,
                    speedRpm = speed,
                    viscosityCp = viscosity.toDoubleOrNull() ?: 0.0,
                    torquePct = torque.toDoubleOrNull() ?: 0.0,
                    temperature = temp,
                    rawString = trimmed
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing serial raw line", e)
        }
        return null
    }
}
