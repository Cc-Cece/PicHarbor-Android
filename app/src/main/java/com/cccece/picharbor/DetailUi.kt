@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.cccece.picharbor

import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun DetailScreen(vm:HarborViewModel,data:HarborUiData) {
    val locale=androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val selected=vm.activeMedia?:return
    val media=data.media.firstOrNull{it.id==selected.id}?:selected
    val status=data.status(media)
    val pair=remember(media,data.media){if(media.video)null else LivePhotoPairing.findPair(media,data.media)}
    var playingLive by remember(media.id){mutableStateOf(false)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("",vm)
        if(media.video||playingLive)VideoPlayer(if(playingLive)pair?.id?:media.id else media.id,autoplay=playingLive) else {
            var scale by remember(media.id){mutableFloatStateOf(1f)}
            var x by remember(media.id){mutableFloatStateOf(0f)};var y by remember(media.id){mutableFloatStateOf(0f)}
            Box(Modifier.fillMaxWidth().height(370.dp).background(Color.Black).clipToBounds().pointerInput(media.id){detectTransformGestures{_,pan,zoom,_->
                scale=(scale*zoom).coerceIn(1f,5f);x=if(scale>1f)(x+pan.x).coerceIn(-size.width*scale/2,size.width*scale/2) else 0f;y=if(scale>1f)(y+pan.y).coerceIn(-size.height*scale/2,size.height*scale/2) else 0f
            }}) {
                AsyncImage(Uri.parse(media.id),media.name,modifier=Modifier.fillMaxSize().graphicsLayer{scaleX=scale;scaleY=scale;translationX=x;translationY=y},contentScale=ContentScale.Fit)
            }
        }
        Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(13.dp)) {
            LText(media.name,localize=false,style=MaterialTheme.typography.titleLarge)
            if(pair!=null||media.pairingID==LivePhotoPairing.EMBEDDED_MOTION)TextButton(onClick={playingLive=!playingLive}){LText(if(playingLive)"显示静态照片" else "播放实况")}
            LText(Instant.ofEpochMilli(media.captured).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT).withLocale(locale)),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            AssistChip(onClick={vm.say(if(status=="uploaded")"已确认当前账号云端存在对应内容；释放空间请使用 Google 相册官方功能" else "备份时先核对云端内容，避免重复上传")},label={LText(when(status){"uploaded"->"已备份到当前账号";"error"->"需要处理";"uploading"->"正在备份";"pending"->"等待备份";else->"备份状态待确认"})},leadingIcon={LIcon(if(status=="uploaded")Icons.Outlined.CloudDone else Icons.Outlined.CloudUpload,null)})
            DetailRow("所在文件夹",media.folderName,valueLocalized=false);DetailRow("文件大小",formatSize(media.size));DetailRow("类型",media.mime)
            if(data.backupMap[media.id]!=null)DetailRow("备份时的画质设置",qualityName(data.backupMap.getValue(media.id).quality))
            if(status=="uploaded")Helper("云端实际画质与存储空间占用，可在 Google 相册详情查看。")
            if(status=="uploaded")Helper("已备份不等于可直接删除。释放空间请使用 Google 相册官方功能。",Icons.Outlined.Security)
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Button(onClick={vm.start(listOf(media.id))},enabled=!vm.busy,modifier=Modifier.weight(1f)){LText(if(status=="uploaded")"重新核对" else "立即备份")}
                FilledTonalButton(onClick={vm.navigate("free-space")},enabled=status=="uploaded",modifier=Modifier.weight(1f)){LText("释放空间")}
            }
            val context=LocalContext.current
            TextButton(onClick={
                val intent=android.content.Intent(android.content.Intent.ACTION_SEND).setType(media.mime)
                    .putExtra(android.content.Intent.EXTRA_STREAM,Uri.parse(media.id))
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(android.content.Intent.createChooser(intent,""))
            }){LText("分享")}
        }
    }
}
@Composable
fun DetailRow(label:String,value:String,valueLocalized:Boolean=true){Row(Modifier.fillMaxWidth().padding(vertical=4.dp),horizontalArrangement=Arrangement.SpaceBetween){LText(label,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant);LText(value,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(start=15.dp),localize=valueLocalized)};HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)}
@Composable
fun VideoPlayer(uri:String,autoplay:Boolean=false) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var error by remember(uri){mutableStateOf(false)}
    val player=remember(uri){ExoPlayer.Builder(context).build().apply{
        addListener(object:androidx.media3.common.Player.Listener{override fun onPlayerError(value:androidx.media3.common.PlaybackException){error=true}})
        setMediaItem(MediaItem.fromUri(uri));playWhenReady=autoplay;prepare()
    }}
    DisposableEffect(player,lifecycle) {
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)player.pause()}
        lifecycle.addObserver(observer)
        onDispose{lifecycle.removeObserver(observer);player.release()}
    }
    if(error)Helper("无法预览动态内容，请检查文件与系统格式支持。")
    AndroidView(factory={PlayerView(it).apply{this.player=player;useController=true}},modifier=Modifier.fillMaxWidth().height(350.dp))
}
