package com.cccece.picharbor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

class MainActivity:ComponentActivity() {
    private val qrCamera=registerForActivityResult(com.journeyapps.barcodescanner.ScanContract()){result->result.contents?.let{model.acceptScan(listOf(it))}}
    private val qrImage=registerForActivityResult(ActivityResultContracts.GetContent()){uri->
        if(uri!=null)lifecycleScope.launch {
            val values=withContext(Dispatchers.IO){runCatching {
                val options=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
                contentResolver.openInputStream(uri)?.use{android.graphics.BitmapFactory.decodeStream(it,null,options)}
                var sample=1;while(maxOf(options.outWidth,options.outHeight)/sample>2048)sample*=2
                options.inJustDecodeBounds=false;options.inSampleSize=sample
                val bitmap=contentResolver.openInputStream(uri)?.use{android.graphics.BitmapFactory.decodeStream(it,null,options)}?:return@runCatching emptyList<String>()
                try {
                    val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                    val source=com.google.zxing.RGBLuminanceSource(bitmap.width,bitmap.height,pixels)
                    val binary=com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
                    com.google.zxing.multi.qrcode.QRCodeMultiReader().decodeMultiple(binary).map{it.text}
                } finally {bitmap.recycle()}
            }.getOrDefault(emptyList())}
            model.acceptScan(values)
        }
    }
    private val model:HarborViewModel by viewModels()
    private var reportRange=LogRange()
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){model.permissionsChanged()}
    private val login=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode==Activity.RESULT_OK)result.data?.getStringExtra("oauth_token")?.let{token->model.afterAction{model.login(token)}}
        else if(result.resultCode==Activity.RESULT_FIRST_USER)model.say("Google 暂未接受此登录环境，请稍后重试")
    }
    private val picker=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->if(uris.isNotEmpty())model.afterAction{model.importFiles(uris)}}
    private val tree=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->if(uri!=null)model.afterAction{model.addTree(uri)}}
    private val report=registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri ->
        if(uri!=null)lifecycleScope.launch {
            model.say("正在导出日志…")
            val message=withContext(Dispatchers.IO){
                runCatching {contentResolver.openOutputStream(uri,"wt")!!.use{Diagnostics.export(it,reportRange)}}.getOrElse { error ->
                    runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver,uri) }
                    Diagnostics.event("bundle.export_failed",attrs=DiagnosticFields.error(error),level="WARN")
                    if(error.message=="NO_LOGS_IN_RANGE")"所选日期范围没有日志" else if(error.message=="EXPORT_BUSY")"已有日志导出任务，请稍候" else "日志导出失败，请重试"
                }
            }
            model.say(message)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model.scanner={image->if(image)qrImage.launch("image/*") else qrCamera.launch(com.journeyapps.barcodescanner.ScanOptions().setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE).setPrompt("扫描二维码").setBeepEnabled(false))}
        if(savedInstanceState!=null)reportRange=LogRange(savedInstanceState.getLong("log_start",Long.MIN_VALUE),savedInstanceState.getLong("log_end",Long.MAX_VALUE),savedInstanceState.getString("log_zone")?:java.time.ZoneId.systemDefault().id)
        if(intent.getBooleanExtra("open_history",false))model.navigate("history")
        lifecycleScope.launch {
            model.graph.preferences.integer("bootSession",bootCount(this@MainActivity));model.graph.scheduler.configure();model.graph.scheduler.restorePending()
        }
        setContent {
            val settings by model.settings.collectAsState()
            MobileLocale(settings.language) {
            var logDialog by remember { mutableStateOf(false) }
            if(logDialog)DiagnosticExportDialog(onDismiss={logDialog=false},onExport={range->
                reportRange=range;logDialog=false
                report.launch("PicHarbor-logs-${java.time.LocalDate.now()}.zip")
            })
            HarborRoot(model,onLogin={login.launch(Intent(this,LoginActivity::class.java))},onPermissions=::requestPermissions,
                onPickFiles={picker.launch(arrayOf("image/*","video/*"))},onPickTree={tree.launch(null)},
                onExport={logDialog=true})
            }
        }
    }
    private fun requestPermissions() {
        val list=if(Build.VERSION.SDK_INT>=33)mutableListOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO)
            else mutableListOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        if(Build.VERSION.SDK_INT>=34)list+=Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        if(Build.VERSION.SDK_INT>=33)list+=Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(list.toTypedArray())
    }
    override fun onStart(){super.onStart();model.graph.visible.set(true);model.graph.computer.startPresence()}
    override fun onStop(){model.graph.visible.set(false);model.graph.computer.stop();super.onStop()}
    override fun onNewIntent(intent:Intent){
        super.onNewIntent(intent);setIntent(intent)
        if(intent.getBooleanExtra("open_history",false))model.navigate("history")
    }
    override fun onResume(){super.onResume();model.permissionsChanged()}
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putLong("log_start",reportRange.start);outState.putLong("log_end",reportRange.end);outState.putString("log_zone",reportRange.zone)
        super.onSaveInstanceState(outState)
    }
}
