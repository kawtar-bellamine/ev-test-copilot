package fr.artsetmetiers.evtestcopilot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.artsetmetiers.evtestcopilot.ui.AppTheme
import fr.artsetmetiers.evtestcopilot.ui.Dashboard

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var pending: (() -> Unit)? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) pending?.invoke()
        else Toast.makeText(this, "Autorisation refusée", Toast.LENGTH_SHORT).show()
        pending = null
    }

    private fun withPermissions(names: Array<String>, action: () -> Unit) {
        val missing = names.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else {
            pending = action
            permissions.launch(missing.toTypedArray())
        }
    }

    private val blePermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                Dashboard(
                    s = state,
                    onDemo = vm::startDemo,
                    onBluetooth = { withPermissions(blePermissions) { vm.connectBluetooth() } },
                    onMic = { withPermissions(arrayOf(Manifest.permission.RECORD_AUDIO)) { vm.listen() } },
                    onMark = vm::mark,
                )
            }
        }
    }
}
