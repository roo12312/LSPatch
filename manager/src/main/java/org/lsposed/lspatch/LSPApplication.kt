package com.lspatch.android

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import com.lspatch.android.manager.AppBroadcastReceiver
import com.lspatch.android.util.LSPPackageManager
import com.lspatch.android.util.ShizukuApi
import java.io.File

lateinit var lspApp: LSPApplication

class LSPApplication : Application() {

    lateinit var prefs: SharedPreferences
    lateinit var tmpApkDir: File

    val globalScope = CoroutineScope(Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        HiddenApiBypass.addHiddenApiExemptions("")
        lspApp = this
        filesDir.mkdir()
        tmpApkDir = cacheDir.resolve("apk").also { it.mkdir() }
        prefs = lspApp.getSharedPreferences("settings", Context.MODE_PRIVATE)
        ShizukuApi.init(this)
        AppBroadcastReceiver.register(this)
        globalScope.launch { LSPPackageManager.fetchAppList() }
    }
}
