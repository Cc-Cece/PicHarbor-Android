package com.cccece.picharbor

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chaquo.python.Python
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.Color
import java.util.UUID
import org.json.JSONObject
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.io.File
import androidx.compose.ui.graphics.toArgb

/** Runs only when installed explicitly as a separately signed test APK. No credential export. */
@RunWith(AndroidJUnit4::class)
class DeviceRegressionTest {
    @Test fun allFoldersPersistence():Unit=runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("phase")=="all_folders")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val graph=(context.applicationContext as PicHarborApp).graph
        graph.startup.await()
        val original=graph.preferences.get()
        val first="picharbor-folders-one@example.invalid"
        val second="picharbor-folders-two@example.invalid"
        try {
            graph.preferences.boolean("auto",false);graph.scheduler.configure()
            graph.backup.stopAndWait()
            graph.preferences.activateAccount(first)
            check(!graph.preferences.get().allFolders)
            graph.preferences.folders(emptySet())
            graph.preferences.boolean("allFolders",true)
            graph.preferences.boolean("videos",false)
            val persisted=Preferences(context).get()
            check(persisted.allFolders&&persisted.folders.isEmpty())
            val future=MediaEntry("test-only","test.jpg","image/jpeg",123,456,456,"future-folder","Future")
            check(persisted.includes(future))
            check(!persisted.includes(future.copy(mime="video/mp4")))
            graph.preferences.activateAccount(second)
            check(!graph.preferences.get().allFolders)
            graph.preferences.activateAccount(first)
            check(graph.preferences.get().allFolders&&graph.preferences.get().folders.isEmpty())
            graph.preferences.boolean("allFolders",false)
            check(!graph.preferences.get().includes(future))
        } finally {
            graph.preferences.activateAccount(original.account)
            graph.preferences.boolean("auto",original.auto)
            graph.preferences.forgetAccount(first);graph.preferences.forgetAccount(second)
            graph.scheduler.configure()
        }
        check(graph.preferences.get()==original)
        File(context.filesDir,"all-folders-acceptance-1.0.8.json").writeText(JSONObject()
            .put("default_disabled",true).put("datastore_persistence",true)
            .put("account_isolation",true).put("future_folder_included",true)
            .put("video_preference_preserved",true).put("empty_explicit_selection_supported",true)
            .put("original_settings_restored",true).put("uploads_or_deletions",0).toString(2))
    }
    @Test fun accountProfileAcceptance():Unit=runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("phase")=="profile_acceptance")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val graph=(context.applicationContext as PicHarborApp).graph
        graph.startup.await()
        val account=graph.preferences.get().account
        val credential=graph.credentials.load(account)
        graph.refreshProfile(account,true).join()
        val profile=withTimeout(45000) {
            var current=graph.credentials.accounts.value.first{it.email==account}
            while(current.avatarPath.isBlank()){delay(100);current=graph.credentials.accounts.value.first{it.email==account}}
            current
        }
        val originalAvatar=File(profile.avatarPath).readBytes()
        val fake="picharbor-profile-test@example.invalid"
        try {
            graph.credentials.save(fake,"disposable-profile-test")
            graph.credentials.saveProfile(fake,"disposable_subject","Test Display",originalAvatar)
            val other=graph.credentials.accounts.value.first{it.email==fake}
            check(other.avatarPath!=profile.avatarPath&&other.displayName=="Test Display")
            check(File(other.avatarPath).isFile)
            graph.credentials.saveProfile(fake,"disposable_subject","Test Display",byteArrayOf(0,1,2))
            check(File(other.avatarPath).readBytes().contentEquals(originalAvatar))
            graph.credentials.remove(fake)
            check(!File(other.avatarPath).exists())
            check(File(profile.avatarPath).readBytes().contentEquals(originalAvatar))
            check(graph.credentials.load(account)==credential)
            File(context.filesDir,"profile-acceptance-1.0.7.json").writeText(JSONObject().put("avatar_cached",true).put("avatar_bytes",originalAvatar.size)
                .put("display_name_available",profile.displayName.isNotBlank()).put("email_name_fallback",profile.displayName.isBlank())
                .put("account_cache_isolation",true).put("invalid_image_rejected",true).put("removed_account_avatar_deleted",true)
                .put("real_credentials_unchanged",true).toString(2))
        } finally {if(graph.credentials.accounts.value.any{it.email==fake})graph.credentials.remove(fake)}
    }
    @Test fun accountProfileProbe():Unit=runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("phase")=="profile_probe")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val graph=(context.applicationContext as PicHarborApp).graph
        graph.ensureAccount()
        val code=instrumentation.context.assets.open("profile_probe.py").bufferedReader().use{it.readText()}
        val globals=Python.getInstance().getModule("builtins").callAttr("dict")
        Python.getInstance().getModule("builtins").callAttr("exec",code,globals)
        val report=JSONObject(globals.callAttr("__getitem__","report_json").toString())
        File(context.filesDir,"profile-probe.json").writeText(report.toString(2))
        check(report.getBoolean("main_auth_unchanged"))
    }
    @Test fun themeForegroundContrast():Unit=runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("phase")=="contrast")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val selected=androidx.compose.runtime.mutableStateOf(HarborSettings(theme="dark",palette="system"))
        val results=java.util.concurrent.ConcurrentHashMap<String,JSONObject>()
        lateinit var probe:androidx.compose.ui.platform.ComposeView
        lateinit var parent:android.view.ViewGroup
        instrumentation.runOnMainSync {
            val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).first()
            parent=activity.findViewById(android.R.id.content)
            // A wrapper avoids changing the activity window while probing themes.
            probe=androidx.compose.ui.platform.ComposeView(android.content.ContextWrapper(activity))
            parent.addView(probe,android.view.ViewGroup.LayoutParams(1,1))
            probe.setContent {
                val settings=selected.value
                PicHarborTheme(settings) {
                    val colors=androidx.compose.material3.MaterialTheme.colorScheme
                    val foreground=androidx.compose.material3.LocalContentColor.current
                    androidx.compose.runtime.SideEffect {
                        fun ratio(f:androidx.compose.ui.graphics.Color,b:androidx.compose.ui.graphics.Color)=androidx.core.graphics.ColorUtils.calculateContrast(f.toArgb(),b.toArgb())
                        results[settings.theme+":"+settings.palette]=JSONObject()
                            .put("inherits_on_background",foreground==colors.onBackground)
                            .put("default_text_contrast",ratio(foreground,colors.background))
                            .put("settings_title_contrast",ratio(colors.onSurface,colors.surfaceContainerLow))
                            .put("secondary_text_contrast",ratio(colors.onSurfaceVariant,colors.surfaceContainerLow))
                    }
                }
            }
        }
        try {
            for(theme in listOf("light","dark"))for(palette in listOf("system","lavender","sage","peach")) {
                instrumentation.runOnMainSync{selected.value=HarborSettings(theme=theme,palette=palette)}
                val key="$theme:$palette"
                withTimeout(10000){while(!results.containsKey(key))delay(25)}
                val result=results.getValue(key)
                check(result.getBoolean("inherits_on_background"))
                listOf("default_text_contrast","settings_title_contrast","secondary_text_contrast").forEach{check(result.getDouble(it)>=4.5)}
            }
            File(instrumentation.targetContext.filesDir,"theme-contrast-1.0.6.json").writeText(JSONObject(results.toSortedMap()).toString(2))
        } finally {instrumentation.runOnMainSync{probe.disposeComposition();parent.removeView(probe)}}
    }
    @Test fun deviceRegression():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val graph=(context.applicationContext as PicHarborApp).graph
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(1000)
        val report=JSONObject().put("android",android.os.Build.VERSION.SDK_INT)
        try {
            graph.ensureAccount()
            report.put("saved_account_count",graph.credentials.accounts.value.size)
            report.put("legacy_migrated",!context.getSharedPreferences("credential",0).contains("data"))
            val code=instrumentation.context.assets.open("cloud_probe.py").bufferedReader().use{it.readText()}
            val globals=Python.getInstance().getModule("builtins").callAttr("dict")
            Python.getInstance().getModule("builtins").callAttr("exec",code,globals)
            report.put("cloud",JSONObject(globals.callAttr("__getitem__","report_json").toString()))
            File(context.filesDir,"device-regression.json").writeText(report.toString(2))
            val cloud=report.getJSONObject("cloud")
            check(!cloud.has("error_type"))
            check(cloud.getJSONObject("normal_cleanup").getBoolean("eligible"))
            check(!cloud.getJSONObject("trashed_cleanup").getBoolean("eligible"))
            check(cloud.getJSONObject("restored_cleanup").getBoolean("eligible"))
        } catch(error:Throwable) {
            report.put("failure_type",error.javaClass.simpleName)
            if(error is EngineFailure)report.put("failure_code",error.code)
            File(context.filesDir,"device-regression.json").writeText(report.toString(2))
            throw AssertionError("Device regression failed; sanitized report saved ("+error.javaClass.simpleName+")")
        }
    }
    @Test fun manualQueueAndVault():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val graph=(context.applicationContext as PicHarborApp).graph
        graph.startup.await()
        val original=graph.preferences.get()
        val report=JSONObject()
        val vaultAccount="picharbor-device-test@example.invalid"
        try {
            graph.ensureAccount()
            val credential=graph.credentials.load(original.account)
            graph.credentials.save(vaultAccount,"disposable-vault-test")
            check(graph.credentials.load(vaultAccount)=="disposable-vault-test")
            check(graph.credentials.load(original.account)==credential)
            graph.credentials.remove(vaultAccount)
            check(graph.credentials.load(original.account)==credential)
            report.put("vault_isolation",true)
            graph.selectAccount(original.account)
            report.put("saved_account_reuse",true)
            graph.scheduler.cancelTransfers();graph.backup.stopAndWait()
            graph.preferences.activateAccount(vaultAccount)
            check(graph.preferences.get().folders==setOf("@camera"))
            graph.preferences.folders(setOf("device-test-folder"))
            graph.preferences.activateAccount(original.account)
            check(graph.preferences.get().folders==original.folders)
            graph.preferences.activateAccount(vaultAccount)
            check(graph.preferences.get().folders==setOf("device-test-folder"))
            graph.preferences.activateAccount(original.account)
            graph.preferences.forgetAccount(vaultAccount)
            report.put("account_folder_isolation",true)
            graph.preferences.boolean("auto",false)
            graph.preferences.string("albumMode","none")
            val name="PicHarbor-disposable-queue-${UUID.randomUUID()}.jpg"
            val values=ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME,name)
                put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/PicHarbor Test")
                put(MediaStore.Images.Media.IS_PENDING,1)
            }
            val image=context.contentResolver.insert(MediaStore.Images.Media.getContentUri("external_primary"),values)!!
            val bitmap=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb((System.currentTimeMillis()%255).toInt(),42,137))
            context.contentResolver.openOutputStream(image)!!.use{bitmap.compress(Bitmap.CompressFormat.JPEG,93,it)}
            bitmap.recycle()
            context.contentResolver.update(image,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)
            val uri=ContentUris.withAppendedId(MediaStore.Files.getContentUri("external_primary"),ContentUris.parseId(image))
            val metadata=context.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.SIZE,MediaStore.MediaColumns.DATE_MODIFIED),null,null,null)!!.use {
                check(it.moveToFirst());it.getLong(0) to it.getLong(1)*1000
            }
            val media=MediaEntry(uri.toString(),name,"image/jpeg",metadata.first,metadata.second,System.currentTimeMillis(),"external_primary|Pictures/PicHarbor Test","PicHarbor Test")
            graph.dao.putMedia(listOf(media))
            check(!graph.preferences.get().includes(media))
            report.put("outside_auto_folders",true).put("auto_disabled",true).put("wifi_detected",graph.backup.onWifi())
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            delay(1500)
            val rounds=org.json.JSONArray()
            repeat(3) {
                val start=android.os.SystemClock.elapsedRealtime()
                val submission=graph.backup.enqueue(listOf(media.id))!!
                check(submission.itemIds.size==1)
                graph.scheduler.launchManual()
                val final=withTimeout(120000) {
                    var item=graph.dao.item(submission.itemIds.single())!!
                    while(item.state !in BackupPolicy.terminalItems&&item.state!="album_pending") {
                        delay(250);item=graph.dao.item(item.id)!!
                    };item
                }
                rounds.put(JSONObject().put("state",final.state).put("error",final.error).put("elapsed_ms",android.os.SystemClock.elapsedRealtime()-start))
                check(final.state in setOf("done","exists"))
            }
            report.put("manual_rounds",rounds).put("fixture_retained",true)
        } catch(error:Throwable) {
            report.put("failure_type",error.javaClass.simpleName)
            if(error is EngineFailure)report.put("failure_code",error.code)
            throw AssertionError("Manual regression failed; sanitized report saved ("+error.javaClass.simpleName+")")
        } finally {
            graph.credentials.remove(vaultAccount)
            if(graph.preferences.get().account!=original.account)graph.preferences.activateAccount(original.account)
            graph.preferences.forgetAccount(vaultAccount)
            graph.preferences.boolean("auto",original.auto)
            graph.preferences.string("albumMode",original.albumMode)
            graph.scheduler.configure()
            File(context.filesDir,"queue-regression.json").writeText(report.toString(2))
        }
    }

}
