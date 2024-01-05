package com.example.ble_peripheral

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityCompat
import com.example.ble_peripheral.ui.theme.BLE_peripheralTheme
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt


class MainActivity : ComponentActivity() {
    private val TAG = "BLEPeripheral"
    private val REQUEST_ENABLE_BT = 1

    private lateinit var bluetoothGattServer: BluetoothGattServer
    private val handler = Handler(Looper.getMainLooper())

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var connected_device_ : HashSet<BluetoothDevice> = HashSet()

    private val service_uuid = UUID.fromString("6e401001-b5a3-f393-e0a9-e50e24dcca9e")

    private val six_dof_characteristics_uuid_ =
        UUID.fromString("6e401002-b5a3-f393-e0a9-e50e24dcca9e")
    private val six_dof_characteristics : BluetoothGattCharacteristic by lazy {
        BluetoothGattCharacteristic(
            six_dof_characteristics_uuid_,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
    }

    private val six_dof_echo_characteristics_uuid_ =
        UUID.fromString("6e400004-b5a3-f393-e0a9-e50e24dcca9e")
    private val six_dof_echo_characteristics : BluetoothGattCharacteristic by lazy {
        BluetoothGattCharacteristic(
            six_dof_echo_characteristics_uuid_,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
    }

    private val buttons_characteristics_uuid_ =
        UUID.fromString("6e401003-b5a3-f393-e0a9-e50e24dcca9e")
    private  val buttons_characteristics : BluetoothGattCharacteristic by lazy {
        BluetoothGattCharacteristic(
            buttons_characteristics_uuid_,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
    }
    private val control_infomation_characteristics_uuid_ =
        UUID.fromString("6e401004-b5a3-f393-e0a9-e50e24dcca9e")
    private  val control_infomation_characteristics : BluetoothGattCharacteristic by lazy {
        BluetoothGattCharacteristic(
            control_infomation_characteristics_uuid_,
            BluetoothGattCharacteristic.PROPERTY_WRITE ,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
    }
    private val gesture_transform_characteristics_uuid_ =
        UUID.fromString("6e401005-b5a3-f393-e0a9-e50e24dcca9e")
    private  val gesture_transform_characteristics : BluetoothGattCharacteristic by lazy {
        BluetoothGattCharacteristic(
            six_dof_echo_characteristics_uuid_,
            BluetoothGattCharacteristic.PROPERTY_WRITE ,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state == BluetoothAdapter.STATE_ON) {
                startAdvertising()
            }
        }
    }
    @SuppressLint("ServiceCast")
    private fun initializeBluetoothGattServer() {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothGattServerCallback = GattServerCallback()
        bluetoothGattServer = bluetoothManager.openGattServer(this, bluetoothGattServerCallback)
        // Add a custom service with the characteristic
        val service = BluetoothGattService(service_uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(six_dof_characteristics)
        service.addCharacteristic(six_dof_echo_characteristics)
        service.addCharacteristic(buttons_characteristics)
        service.addCharacteristic(control_infomation_characteristics);
        service.addCharacteristic(gesture_transform_characteristics);
        bluetoothGattServer.addService(service)
    }

    private val dataSenderRunnable = object : Runnable {
        override fun run() {
            // Notify subscribed devices with new data
            sendDataToSubscribers()

            // Schedule the next execution after 1 second
            handler.postDelayed(this, 1000)
        }
    }

    private fun sendDataToSubscribers() {
        // Generate data to be sent
        val data = generateData()

        // Update the characteristic value
        //customCharacteristic.value = data.toByteArray()
        six_dof_characteristics.value = data.toByteArray();

        // Notify subscribed devices
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            Log.e(TAG, "CONNECTPermission")
            return
        }
        /*
        if (connected_device_.isNotEmpty()) {
            connected_device_.forEach {
                it ->
                bluetoothGattServer.notifyCharacteristicChanged(
                    /* device= */ it,
                    six_dof_characteristics,
                    /* confirm= */ false
                    //data.toByteArray()
                )
            }
        }

         */
    }

    private fun SendSixDof(timestamp : Long, quaternion : FloatArray) {
        //val init =
        val position = FloatArray(3) { it -> it.toFloat() }
        //
        val buffer = ByteBuffer.allocate(java.lang.Long.BYTES + 7 * java.lang.Float.BYTES).order(
            ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(timestamp)
        buffer.putFloat(position[0])
        buffer.putFloat(position[1])
        buffer.putFloat(position[2])
        buffer.putFloat(quaternion[0])
        buffer.putFloat(quaternion[1])
        buffer.putFloat(quaternion[2])
        buffer.putFloat(quaternion[3])
        val data = buffer.array();
        Log.i(TAG, "SixDof data length " + data.size)
        six_dof_characteristics.value = data
        if (connected_device_.isNotEmpty()) {
            connected_device_.forEach {
                    it ->
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    // TODO: Consider calling
                    //    ActivityCompat#requestPermissions
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for ActivityCompat#requestPermissions for more details.
                    return
                }
                bluetoothGattServer.notifyCharacteristicChanged(
                    /* device= */ it,
                    six_dof_characteristics,
                    /* confirm= */ false,
                    data
                )
            }
        }
    }
    private fun generateData(): String {
        // Replace this with your actual data generation logic
        return UUID.randomUUID().toString()
    }
    private inner class GattServerCallback : BluetoothGattServerCallback() {

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            super.onMtuChanged(device, mtu)
            Log.i(TAG, "MTU Changed to " + mtu)
        }
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            // Handle connection state changes
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected_device_.add(device)
                Log.i(TAG, "Device " + device.name + " connected")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected_device_.remove(device)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {


            bluetoothGattServer.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                generateData().toByteArray()
            )
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            // Handle write requests for the characteristic
            characteristic.value = value

            if (responseNeeded) {
                bluetoothGattServer.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value
                )
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            Log.i(TAG, "onDescriptorWriteRequest")

            val uuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            if (descriptor.uuid == uuid) {
                // Check if the CCCD is being written (subscribe/unsubscribe)
                if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)) {
                    // Characteristic is subscribed
                    handleCharacteristicSubscribed(device, descriptor.characteristic)
                } else if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, value)) {
                    // Characteristic is unsubscribed
                    handleCharacteristicUnsubscribed(device, descriptor.characteristic)
                }
                // Respond to the descriptor write request
                if (responseNeeded) {
                    bluetoothGattServer.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        value
                    )
                }
            } else {
                // Handle other descriptor write requests
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            // Handle notification sent status
        }
    }
    private fun handleCharacteristicSubscribed(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic) {
        // Characteristic is subscribed, perform necessary actions
        // For example, you can keep track of subscribed devices in a list
        // or trigger specific behavior when a device subscribes to the characteristic
        //device_ = device
    }

    private fun handleCharacteristicUnsubscribed(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic) {
        // Characteristic is unsubscribed, perform necessary actions
        // For example, you can update your list of subscribed devices
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BLE_peripheralTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (CheckPermissions()) {
                        Greeting("Master")
                    } else {
                        RequestPermissions()
                        Greeting("RequestPermission")
                    }
                }
            }
        }
        initializeBluetoothGattServer()
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth is not supported on this device")
            finish()
            return
        }

        if (!bluetoothAdapter!!.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
        } else {
            startAdvertising()
        }
        handler.postDelayed(dataSenderRunnable, 1000)
        // Initialize SensorManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Get rotation vector sensor
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        //if (rotationVectorSensor == null) {
            // Rotation vector sensor is not available on this device
            // Handle accordiendgly
        //}
    }
    private fun startAdvertising() {
        bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (bluetoothLeAdvertiser == null) {
            Log.e(TAG, "Failed to obtain BluetoothLeAdvertiser")
            finish()
            return
        }

        // Set up advertising data
        // Set up this device as a left controller with the manufacture data assgined to 0
        // the right controller is assgined to 1
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            //.addServiceUuid(ParcelUuid(service_uuid))
            .addManufacturerData(0, ByteArray(1){it -> 0})
            .build()
        val scanResponseData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(service_uuid))  // Actual service UUID
            //.addServiceData(ParcelUuid(yourServiceUuid), yourAdditionalData)
            .build()

        // Set up advertising settings
        val advertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        // Start advertising
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            Log.e(TAG, "StartAdvertisingFails")
            return
        }
        Log.i(TAG, "StartAdvertising")
        bluetoothLeAdvertiser?.startAdvertising(advertiseSettings, advertiseData, scanResponseData, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE) {
                Log.e(TAG, "Advertising failed DATA TOO LARGE")
            } else if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS) {
                Log.e(TAG, "Advertising failed TOO MANY ADVERTISERS")
            } else if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR) {
                Log.e(TAG, "Advertising failed INTERNAL ERROR")
            } else if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED) {
                Log.e(TAG, "Advertising failed ALREADY STARTED")
            } else {

            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        // Register the sensor listener
        // Register the sensor listener
        rotationVectorSensor?.let {
            sensorManager.registerListener(
                rotationVectorListener,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(bluetoothStateReceiver)
        // Unregister the sensor listener to save power
        sensorManager.unregisterListener(rotationVectorListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
    }

    private val required_permission = listOf<String>(
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT
    )
    fun CheckPermissions() : Boolean {
        val transform : (String) -> Boolean = { this.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED };
        return required_permission.all(transform)
    }

    fun RequestPermissions() {
        this.requestPermissions(required_permission.toTypedArray(), REQUEST_ENABLE_BT);
    }

    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null

    private val rotationVectorListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // Handle accuracy changes if needed
        }

        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                val rotationVector = FloatArray(4)
                System.arraycopy(event.values, 0, rotationVector, 0, 4)

                // Optionally, normalize the rotation vector
                val magnitude = sqrt(
                    rotationVector[0] * rotationVector[0] +
                            rotationVector[1] * rotationVector[1] +
                            rotationVector[2] * rotationVector[2] +
                            rotationVector[3] * rotationVector[3]
                )
                for (i in 0 until 4) {
                    rotationVector[i] /= magnitude
                }

                // Use the rotation vector as needed
                // ...

                // Log the rotation vector components
                println("Rotation Vector: [${rotationVector[0]}, ${rotationVector[1]}, ${rotationVector[2]}, ${rotationVector[3]}]")
                // x, y, z, w
                val timestamp = event.timestamp

                // convert to w, x, y ,z

                val quaternion = FloatArray(4)
                quaternion[0] = rotationVector[3];
                quaternion[1] = rotationVector[0];
                quaternion[2] = rotationVector[1];
                quaternion[3] = rotationVector[2];
                SendSixDof(timestamp,quaternion);
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    BLE_peripheralTheme {
        Greeting("Android")
    }
}