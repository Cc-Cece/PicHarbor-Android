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
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun DetailScreen(vm:HarborViewModel,data:HarborUiData) {
    val media=vm.activeMedia?:return
    val status=data.status(media)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("",vm,action={IconButton(onClick={vm.selectAll(listOf(media.id));vm.sheet="delete-local"}){Icon(Icons.Outlined.DeleteOutline,"从手机删除")}})
        if(media.video)VideoPlayer(media.id) else {
            var scale by remember(media.id){mutableFloatStateOf(1f)}
            var x by remember(media.id){mutableFloatStateOf(0f)};var y by remember(media.id){mutableFloatStateOf(0f)}
            Box(Modifier.fillMaxWidth().height(370.dp).background(Color.Black).clipToBounds().pointerInput(media.id){detectTransformGestures{_,pan,zoom,_->
                scale=(scale*zoom).coerceIn(1f,5f);x=if(scale>1f)(x+pan.x).coerceIn(-size.width*scale/2,size.width*scale/2) else 0f;y=if(scale>1f)(y+pan.y).coerceIn(-size.height*scale/2,size.height*scale/2) else 0f
            }}) {
                AsyncImage(Uri.parse(media.id),media.name,modifier=Modifier.fillMaxSize().graphicsLayer{scaleX=scale;scaleY=scale;translationX=x;translationY=y},contentScale=ContentScale.Fit)
            }
        }
        Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(13.dp)) {
            Text(media.name,style=MaterialTheme.typography.titleLarge)
            Text(Instant.ofEpochMilli(media.captured).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            AssistChip(onClick={vm.say(if(status=="uploaded")"已确认当前账号云端存在对应内容；清理前仍会再次核验" else "备份时先核对云端内容，避免重复上传")},label={Text(when(status){"uploaded"->"已备份到当前账号";"error"->"需要处理";"uploading"->"正在备份";"pending"->"等待备份";else->"备份状态待确认"})},leadingIcon={Icon(if(status=="uploaded")Icons.Outlined.CloudDone else Icons.Outlined.CloudUpload,null)})
            DetailRow("所在文件夹",media.folderName);DetailRow("文件大小",formatSize(media.size));DetailRow("类型",media.mime)
            if(data.backupMap[media.id]!=null)DetailRow("备份时的画质设置",qualityName(data.backupMap.getValue(media.id).quality))
            if(status=="uploaded")Helper("云端实际画质与存储空间占用，可在 Google 相册详情查看。")
            if(status=="uploaded")Helper("已备份不等于可直接删除。释放空间会独立核验当前云端与本地内容。",Icons.Outlined.Security)
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Button(onClick={vm.start(listOf(media.id))},enabled=!vm.busy,modifier=Modifier.weight(1f)){Text(if(status=="uploaded")"重新核对" else "立即备份")}
                FilledTonalButton(onClick={vm.navigate("cleanup")},enabled=status=="uploaded",modifier=Modifier.weight(1f)){Text("释放空间")}
            }
        }
    }
}
@Composable
fun DetailRow(label:String,value:String){Row(Modifier.fillMaxWidth().padding(vertical=4.dp),horizontalArrangement=Arrangement.SpaceBetween){Text(label,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(start=15.dp))};HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)}
@Composable
fun VideoPlayer(uri:String) {
    val context=LocalContext.current
    val player=remember(uri){ExoPlayer.Builder(context).build().apply{setMediaItem(MediaItem.fromUri(uri));prepare()}}
    DisposableEffect(player) {
        val lifecycle=(context as? LifecycleOwner)?.lifecycle
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)player.pause()}
        lifecycle?.addObserver(observer)
        onDispose{lifecycle?.removeObserver(observer);player.release()}
    }
    AndroidView(factory={PlayerView(it).apply{this.player=player;useController=true}},modifier=Modifier.fillMaxWidth().height(350.dp))
}
