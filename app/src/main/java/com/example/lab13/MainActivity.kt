package com.example.lab13

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.Blue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.example.lab13.ui.theme.Lab13Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class MyViewModel : ViewModel() {

    companion object GattAttributes {
        const val SCAN_PERIOD: Long = 3000
        const val STATE_DISCONNECTED = 0
        const val STATE_CONNECTING = 1
        const val STATE_CONNECTED = 2
        val UUID_HEART_RATE_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val UUID_HEART_RATE_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val UUID_CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    val scanResults = MutableLiveData<List<ScanResult>>(null)
    val isScanning = MutableLiveData<Boolean>(false)
    private val scannedDevices = HashMap<String, ScanResult>()
    val connectionState = MutableLiveData<Int>(-1)
    val heartRateBPM = MutableLiveData<Int>(0)
    private var bluetoothGatt: BluetoothGatt? = null

    fun startDeviceScan(scanner: BluetoothLeScanner) {
        viewModelScope.launch(Dispatchers.IO) {
            isScanning.postValue(true)
            scannedDevices.clear()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build()
            scanner.startScan(null, settings, scanCallback)
            delay(SCAN_PERIOD)
            scanner.stopScan(scanCallback)
            scanResults.postValue(scannedDevices.values.toList())
            isScanning.postValue(false)
        }
    }

    fun connectToDevice(context: Context, device: BluetoothDevice) {
        Log.d("DBG", "Connecting to ${device.name} ${device.address}")
        connectionState.postValue(STATE_CONNECTING)
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnectFromDevice() {
        Log.d("DBG", "Disconnecting from device")
        bluetoothGatt?.disconnect()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            scannedDevices[result.device.address] = result
            Log.d("DBG", "Device found: ${result.device.address} (${result.isConnectable})")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectionState.postValue(STATE_CONNECTED)
                    Log.i("DBG", "Connected to GATT server. Starting service discovery...")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectionState.postValue(STATE_DISCONNECTED)
                    heartRateBPM.postValue(0)
                    gatt.disconnect()
                    gatt.close()
                    Log.i("DBG", "Disconnected from GATT server")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("DBG", "Service discovery failed")
                return
            }
            Log.d("DBG", "Services discovered")

            for (service in gatt.services) {
                Log.d("DBG", "Service UUID: ${service.uuid}")
                if (service.uuid == UUID_HEART_RATE_SERVICE) {
                    Log.d("DBG", "Heart Rate Service found")
                    setupNotifications(gatt, service)
                }
            }
        }

        private fun setupNotifications(gatt: BluetoothGatt, service: BluetoothGattService) {
            val characteristic = service.getCharacteristic(UUID_HEART_RATE_MEASUREMENT)
            if (gatt.setCharacteristicNotification(characteristic, true)) {
                val descriptor = characteristic.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG)
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == UUID_HEART_RATE_MEASUREMENT) {
                heartRateBPM.postValue(extractHeartRate(characteristic))
            }
        }

        private fun extractHeartRate(characteristic: BluetoothGattCharacteristic): Int {
            val format = if (characteristic.properties and 0x01 != 0) {
                BluetoothGattCharacteristic.FORMAT_UINT16
            } else {
                BluetoothGattCharacteristic.FORMAT_UINT8
            }
            return characteristic.getIntValue(format, 1) ?: -1
        }
    }
}

@Composable
fun ShowDevices(bluetoothAdapter: BluetoothAdapter, model: MyViewModel = viewModel()) {
    val context = LocalContext.current
    val devices: List<ScanResult>? by model.scanResults.observeAsState(null)
    val scanning: Boolean by model.isScanning.observeAsState(false)
    val connectionState: Int by model.connectionState.observeAsState(-1)
    val bpm: Int by model.heartRateBPM.observeAsState(0)

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = { model.startDeviceScan(bluetoothAdapter.bluetoothLeScanner) },
            enabled = !scanning,
            modifier = Modifier.padding(8.dp).height(35.dp).width(320.dp),
        ) {
            Text(if (scanning) "Scanning" else "Scan Now")
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp), thickness = 1.dp, color = Color.Gray)

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = when (connectionState) {
                MyViewModel.STATE_CONNECTED -> "Connected"
                MyViewModel.STATE_CONNECTING -> "Connecting..."
                MyViewModel.STATE_DISCONNECTED -> "Disconnected"
                else -> ""
            })

            if (connectionState == MyViewModel.STATE_CONNECTED) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(text = if (bpm != 0) "$bpm" else "--", fontSize = 36.sp)
                    Text(text = "BPM", fontSize = 12.sp)
                }
                Button(
                    onClick = { model.disconnectFromDevice() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red, contentColor = Color.White)
                ) {
                    Text("Disconnect")
                }
            }
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp), thickness = 1.dp, color = Color.Gray)

        if (devices.isNullOrEmpty()) {
            Text(text = "No devices found", modifier = Modifier.padding(8.dp))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                items(devices!!) { result ->
                    DeviceItem(result = result, model = model, context = context)
                }
            }
        }
    }
}

@Composable
fun DeviceItem(result: ScanResult, model: MyViewModel, context: Context) {
    val deviceName = result.device.name ?: "UNKNOWN"
    val deviceAddress = result.device.address
    val deviceSignalStrength = result.rssi

    Row(
        modifier = Modifier
            .padding(8.dp)
            .background(Color.Black)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "${deviceSignalStrength}dBm",
            modifier = Modifier
                .padding(end = 10.dp)
                .align(Alignment.CenterVertically),
            color = Color.White
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = deviceName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = deviceAddress,
                color = Color.Gray
            )
        }

        Button(
            enabled = result.isConnectable(),
            onClick = { model.connectToDevice(context, result.device) },
            modifier = Modifier
                .padding(8.dp)
                .align(Alignment.CenterVertically),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Blue,
                contentColor = Color.White
            )
        ) {
            Text("Connect")
        }
    }
}

class MainActivity : ComponentActivity() {
    private var bluetoothAdapter: BluetoothAdapter? = null

    private fun checkPermissions(): Boolean {
        if (bluetoothAdapter == null ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Lab13Theme {
                bluetoothAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                if (!checkPermissions()) {
                }
                ShowDevices(bluetoothAdapter!!)
            }
        }
    }
}