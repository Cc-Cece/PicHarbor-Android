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
    @Test fun policyWarningUi():Unit=runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("phase")=="policy_ui")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val graph=(instrumentation.targetContext.applicationContext as PicHarborApp).graph
        graph.startup.await()
        check(graph.preferences.get().cleanupPolicy.complete)
        fun walk(node:android.view.accessibility.AccessibilityNodeInfo?):Sequence<android.view.accessibility.AccessibilityNodeInfo> = sequence {
            if(node!=null){yield(node);for(i in 0 until node.childCount)yieldAll(walk(node.getChild(i)))}
        }
        suspend fun find(text:String):android.view.accessibility.AccessibilityNodeInfo {
            repeat(20) {
                val nodes=walk(instrumentation.uiAutomation.rootInActiveWindow).toList()
                nodes.firstOrNull{it.text?.toString()==text}?.let{return it}
                nodes.firstOrNull{it.isScrollable}?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                delay(250)
            }
            error("Required test control was not found: $text")
        }
        fun control(node:android.view.accessibility.AccessibilityNodeInfo):android.view.accessibility.AccessibilityNodeInfo {
            var current=node
            while(!current.isClickable&&current.className?.toString()!="android.widget.Button"&&current.parent!=null)current=current.parent
            return current
        }
        suspend fun click(text:String){check(control(find(text)).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));delay(600)}
        click("我的");click("高级设置");click("空间检查流程");click("最新云端状态核验")
        fun field()=walk(instrumentation.uiAutomation.rootInActiveWindow).first{it.className?.toString()=="android.widget.EditText"}
        fun enter(value:String){check(field().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply{putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)}))}
        enter("wrong");delay(300)
        check(!control(find("确认降低检查强度")).isEnabled)
        enter(CleanupPolicy.ACKNOWLEDGEMENT);delay(300)
        var enabled=false
        repeat(20){if(!enabled){delay(100);enabled=control(find("确认降低检查强度")).isEnabled}}
        if(!enabled)File(instrumentation.targetContext.filesDir,"policy-ui-debug.json").writeText(JSONObject().put("exact_input_visible",field().text?.toString()==CleanupPolicy.ACKNOWLEDGEMENT).put("input_length",field().text?.length?:-1).put("confirm_enabled",enabled).toString())
        check(enabled)
        click("保留原设置")
        check(graph.preferences.get().cleanupPolicy.complete)
        File(instrumentation.targetContext.filesDir,"policy-ui-1.0.5.json").writeText(JSONObject().put("wrong_text_disables_confirmation",true).put("exact_text_enables_confirmation",true).put("cancel_preserves_full_policy",true).toString(2))
    }
    @Test fun cleanupPolicyRegression():Unit=runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("phase")=="policy")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val graph=(context.applicationContext as PicHarborApp).graph
        graph.startup.await();graph.cleanup.stopAndWait()
        val original=graph.preferences.get()
        val report=JSONObject()
        try {
            graph.scheduler.cancelTransfers();graph.backup.stopAndWait()
            graph.preferences.boolean("auto",false)
            val full=CleanupPolicy()
            graph.cleanup.changePolicy(PendingCleanupPolicy(original.account,original.cleanupPolicy,full))
            val reduced=CleanupPolicy(false,false)
            val change=PendingCleanupPolicy(original.account,full,reduced)
            var rejected=false
            try{graph.cleanup.changePolicy(change,"wrong")}catch(error:EngineFailure){rejected=error.code=="RISK_CONFIRMATION_REQUIRED"}
            check(rejected&&graph.preferences.get().cleanupPolicy.complete)
            report.put("incorrect_acknowledgement_rejected",true)
            graph.cleanup.changePolicy(change,CleanupPolicy.ACKNOWLEDGEMENT)
            check(graph.preferences.get().cleanupPolicy.id=="basic")
            report.put("explicit_acknowledgement_accepted",true)
            val target=graph.dao.media().first{it.name.startsWith("PicHarbor-disposable-queue-")&&it.folderName=="PicHarbor Test"}
            val scan=graph.cleanup.enqueue(listOf(target.id),false)
            graph.cleanup.runScan(scan.id)
            val ready=graph.dao.readyCleanup(original.account).first{it.mediaId==target.id&&it.scanId==scan.id}
            check(ready.policy=="basic")
            check(!BackupPolicy.cleanupFresh(ready,original.account,System.currentTimeMillis()))
            check(BackupPolicy.cleanupFresh(ready,original.account,System.currentTimeMillis(),"basic"))
            val prepared=graph.cleanup.prepareDelete()
            check(prepared.size==1&&prepared.single().id==ready.id)
            report.put("basic_proof_is_not_full_proof",true).put("deletion_boundary_checks_passed_without_deleting",true)
            graph.cleanup.changePolicy(PendingCleanupPolicy(original.account,reduced,full))
            check(graph.dao.cleanupByIds(listOf(ready.id)).single().state=="expired")
            check(graph.cleanup.prepareDelete().isEmpty())
            report.put("policy_change_expires_old_eligibility",true)
            val done=graph.dao.cleanupScan(scan.id)!!
            val metrics=JSONObject(done.metrics)
            check(!metrics.has("cloud_sync")&&!metrics.has("original_url"))
            report.put("disabled_steps_not_run",true).put("room_version",graph.database.openHelper.readableDatabase.version)
            File(context.filesDir,"policy-regression-1.0.5.json").writeText(report.toString(2))
        } finally {
            graph.cleanup.stopAndWait()
            val current=graph.preferences.get()
            graph.cleanup.changePolicy(PendingCleanupPolicy(current.account,current.cleanupPolicy,original.cleanupPolicy),CleanupPolicy.ACKNOWLEDGEMENT)
            graph.preferences.boolean("auto",original.auto);graph.scheduler.configure()
        }
    }
    @Test fun checkPerformance():Unit=runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("phase")=="performance")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val graph=(context.applicationContext as PicHarborApp).graph
        graph.startup.await()
        val original=graph.preferences.get()
        val report=JSONObject()
        try {
            graph.scheduler.cancelTransfers();graph.backup.stopAndWait();graph.cleanup.stopAndWait()
            graph.preferences.boolean("auto",false)
            graph.preferences.boolean("cleanupAdaptive",false)
            graph.preferences.integer("cleanupReaders",2)
            graph.preferences.integer("cleanupRetries",0)
            graph.ensureAccount()
            val records=graph.dao.backups(original.account).associateBy{it.mediaId}
            val targets=graph.dao.media().filter{BackupPolicy.matches(it,records[it.id])}.take(24).map{it.id}
            check(targets.isNotEmpty())
            val before=graph.dao.readyCleanup(original.account)
            val quick=graph.cleanup.enqueue(targets,false,"quick")
            graph.cleanup.runScan(quick.id)
            val quickDone=graph.dao.cleanupScan(quick.id)!!
            check(quickDone.state=="completed"&&quickDone.checked==targets.size)
            check(graph.dao.readyCleanup(original.account)==before)
            val quickMetrics=JSONObject(quickDone.metrics)
            check(!quickMetrics.has("cloud_sync")&&!quickMetrics.has("original_url"))
            check(quickMetrics.getJSONObject("content_query").getInt("count")==targets.size)
            report.put("quick",JSONObject().put("elapsed_ms",quickDone.elapsedMs).put("metrics",quickMetrics).put("cleanup_evidence_unchanged",true))
            val runs=org.json.JSONArray()
            for(parallel in listOf(2,4,8,20)) {
                graph.preferences.integer("cleanupConcurrency",parallel)
                check(graph.preferences.get().cleanupConcurrency==parallel)
                val scan=graph.cleanup.enqueue(targets,false)
                graph.cleanup.runScan(scan.id)
                val done=graph.dao.cleanupScan(scan.id)!!
                check(done.state=="completed"&&done.checked==targets.size)
                val values=JSONObject(done.metrics)
                check(values.getJSONObject("cloud_sync").getInt("count")>=1)
                check(values.getJSONObject("file_read").getInt("count")==targets.size)
                runs.put(JSONObject().put("maximum_parallel",parallel).put("readers",2).put("elapsed_ms",done.elapsedMs).put("metrics",values)
                    .put("ready",graph.dao.readyCleanup(original.account).size))
                File(context.filesDir,"check-performance.json").writeText(report.put("full_runs",runs).put("sample_count",targets.size).toString(2))
            }
            report.put("room_version",graph.database.openHelper.readableDatabase.version)
            check(graph.database.openHelper.readableDatabase.version==3)
            File(context.filesDir,"check-performance.json").writeText(report.toString(2))
        } finally {
            graph.preferences.integer("cleanupConcurrency",original.cleanupConcurrency)
            graph.preferences.integer("cleanupReaders",original.cleanupReaders)
            graph.preferences.integer("cleanupRetries",original.cleanupRetries)
            graph.preferences.boolean("cleanupAdaptive",original.cleanupAdaptive)
            graph.preferences.boolean("auto",original.auto);graph.scheduler.configure()
        }
    }
    @Before fun visibleActivity():Unit=runBlocking {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf("input keyevent 224","wm dismiss-keyguard","cmd statusbar collapse").forEach{command->automation.executeShellCommand(command).use{descriptor->android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use{it.readBytes()}}}
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(1000)
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

    @Test fun cleanupCurrentAccount():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val graph=(context.applicationContext as PicHarborApp).graph
        val account=graph.ensureAccount()
        graph.cleanup.verify()
        val rows=graph.dao.cleanupFlow(account).first()
        val report=JSONObject().put("ready",rows.count{it.state=="ready"})
            .put("blocked",rows.count{it.state=="blocked"})
            .put("reasons",JSONObject(rows.filter{it.state=="blocked"}.groupingBy{it.reason}.eachCount()))
        File(context.filesDir,"cleanup-regression.json").writeText(report.toString(2))
        check(rows.any{it.state=="ready"})
    }

    @Test fun prepareDisposableCleanup():Unit=runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("phase")=="cleanup_prepare")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val graph=(context.applicationContext as PicHarborApp).graph
        val account=graph.ensureAccount()
        val media=graph.dao.media().filter{it.folderName=="PicHarbor Test"&&it.name.startsWith("PicHarbor-disposable-queue-")}.maxBy{it.captured}
        check(graph.dao.backup(account,media.id)!=null)
        graph.cleanup.verify(listOf(media.id))
        val ready=graph.dao.readyCleanup(account)
        check(ready.size==1&&ready.single().mediaId==media.id)
        val prepared=graph.cleanup.prepareDelete()
        check(prepared.size==1)
        try {
            check(graph.cleanup.systemRequest(prepared)!=null)
        } catch(error:Exception) {
            File(context.filesDir,"delete-authorization-error.json").writeText(JSONObject().put("type",error.javaClass.simpleName).toString())
            throw AssertionError("Delete authorization creation failed: "+error.javaClass.simpleName)
        }
        File(context.filesDir,"disposable-cleanup-id.txt").writeText(media.id)
        File(context.filesDir,"disposable-cleanup-prepared.json").writeText(JSONObject().put("ready",1).put("only_disposable_fixture",true).toString())
    }
    @Test fun confirmDisposableCleanup():Unit=runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("phase")=="cleanup_confirm")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val graph=(context.applicationContext as PicHarborApp).graph
        val account=graph.ensureAccount()
        val id=File(context.filesDir,"disposable-cleanup-id.txt").readText()
        val row=graph.dao.cleanupFlow(account).first().single{it.mediaId==id}
        check(row.state=="deleted")
        val missing=graph.engine.call("reconcile",JSONObject().put("keys",org.json.JSONArray(listOf(row.remoteKey)))).getJSONArray("missing")
        val report=JSONObject().put("local_removed",graph.dao.media(id)==null).put("cloud_retained",missing.length()==0).put("system_flow_deleted",true)
        File(context.filesDir,"disposable-cleanup-result.json").writeText(report.toString(2))
        check(report.getBoolean("local_removed")&&report.getBoolean("cloud_retained"))
    }

    @Test fun prepareBackgroundCleanup():Unit=runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("phase")=="background_prepare")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val graph=(context.applicationContext as PicHarborApp).graph
        graph.startup.await()
        val targets=graph.dao.media().filter{it.name.startsWith("PicHarbor-disposable-queue-")&&it.folderName=="PicHarbor Test"}.take(6)
        check(targets.size>=3)
        val scan=graph.cleanup.enqueue(targets.map{it.id})
        File(context.filesDir,"background-scan-id.txt").writeText(scan.id)
        val deadline=android.os.SystemClock.elapsedRealtime()+60000
        var current=graph.dao.cleanupScan(scan.id)!!
        while(current.checked==0&&current.state!in setOf("completed","failed")&&android.os.SystemClock.elapsedRealtime()<deadline) {
            delay(100);current=graph.dao.cleanupScan(scan.id)!!
        }
        val notifications=context.getSystemService(android.app.NotificationManager::class.java).activeNotifications
        val ongoing=notifications.firstOrNull{it.id==CleanupNotifications.ID}
        File(context.filesDir,"background-start.json").writeText(JSONObject().put("checked",current.checked).put("total",current.total)
            .put("state",current.state).put("notification_progress",ongoing?.notification?.extras?.getInt(android.app.Notification.EXTRA_PROGRESS,-1)?:-1)
            .put("notification_max",ongoing?.notification?.extras?.getInt(android.app.Notification.EXTRA_PROGRESS_MAX,-1)?:-1).toString(2))
        check(current.checked>0)
        check(ongoing!=null||current.state=="completed")
    }
    @Test fun confirmBackgroundCleanup():Unit=runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("phase")=="background_confirm")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val graph=(context.applicationContext as PicHarborApp).graph
        val id=File(context.filesDir,"background-scan-id.txt").readText()
        val final=withTimeout(180000) {
            var current=graph.dao.cleanupScan(id)!!
            while(current.state !in setOf("completed","failed","cancelled")) {delay(250);current=graph.dao.cleanupScan(id)!!}
            current
        }
        val tasks=graph.dao.cleanupScanItems(id)
        val completed=context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any{it.id==id.hashCode()}
        val report=JSONObject().put("state",final.state).put("checked",final.checked).put("total",final.total)
            .put("all_checkpoints_saved",tasks.all{it.done}).put("completion_notification",completed)
            .put("room_version",graph.database.openHelper.readableDatabase.version)
            .put("parallelism",graph.preferences.get().cleanupConcurrency)
        File(context.filesDir,"background-result.json").writeText(report.toString(2))
        check(final.state=="completed"&&final.checked==final.total&&tasks.all{it.done})
        check(completed)
    }

}
