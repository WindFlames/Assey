package io.savant.assey

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.savant.assey.ui.AppController
import io.savant.assey.ui.AsseyRoot
import io.savant.assey.ui.theme.AsseyTheme

class MainActivity : ComponentActivity() {

    private lateinit var controller: AppController

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as AsseyApp
        controller = AppController(app.container)
        savedInstanceState?.getString(KEY_ROUTE)?.let { controller.route.value = it }

        // 先恢复未完成任务，再处理分享/打开意图，避免把刚导入的任务误判为中断
        app.container.recoverInterruptedTasks()

        setContent {
            AsseyTheme {
                AsseyRoot(controller)
            }
        }

        if (savedInstanceState == null) {
            handleIntent(intent)
        }
        askNotificationPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_ROUTE, controller.route.value)
        super.onSaveInstanceState(outState)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        val taskId = intent.getStringExtra(EXTRA_OPEN_TASK)
        if (taskId != null) {
            controller.openTask(taskId)
            intent.removeExtra(EXTRA_OPEN_TASK)
            return
        }

        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = getStream(intent)
                if (uri != null) {
                    controller.importUri(uri)
                }
            }

            Intent.ACTION_VIEW -> {
                intent.data?.let { controller.importUri(it) }
            }
        }
    }

    private fun getStream(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_TASK = "open_task_id"
        private const val KEY_ROUTE = "route"
    }
}
