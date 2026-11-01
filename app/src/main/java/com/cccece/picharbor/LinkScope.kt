package com.cccece.picharbor

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** Versioned selections are strict: unknown versions never become an all-media request. */
internal data class LinkScope(
    val mode:String="all", val albums:Set<String> = emptySet(), val dateEnabled:Boolean=false,
    val from:String="", val to:String="", val photos:Boolean=true, val videos:Boolean=true,
    val ignoreSmallImages:Boolean=false, val minImageKb:Int=100, val filterCache:Boolean=false
) {
    companion object {
        fun parse(wire:String):LinkScope {
            if(wire=="all"||wire=="library"||wire=="selected")return LinkScope()
            if(wire.startsWith("range:")) {
                val p=wire.split(':'); require(p.size==3){"日期范围无效"}
                return LinkScope(mode="date",dateEnabled=true,from=p[1],to=p[2]).validated()
            }
            require(wire.startsWith("scope2:")){"不支持的备份范围，请更新应用"}
            val j=JSONObject(wire.removePrefix("scope2:"))
            val a=j.optJSONArray("albums")?:JSONArray()
            return LinkScope(j.getString("mode"),(0 until a.length()).map{a.getString(it)}.toSet(),
                j.optBoolean("dateEnabled"),j.optString("from").takeUnless{it=="null"}.orEmpty(),
                j.optString("to").takeUnless{it=="null"}.orEmpty(),j.optBoolean("photos",true),j.optBoolean("videos",true),
                j.optBoolean("ignoreSmallImages",true),j.optInt("minImageKb",100),j.optBoolean("filterCache",true)).validated()
        }
        fun decoded(path:String)=runCatching{java.net.URLDecoder.decode(path,"UTF-8")}.getOrDefault(path)
        fun camera(path:String)=path.split('/','|').any{it.equals("Camera",true)||it.equals("Screenshots",true)||it.equals("ScreenCapture",true)}
        fun time(e:MediaEntry)= (if(e.captured>0)e.captured else e.modified).let{if(it in 1..9_999_999_999L)it*1000 else it}
        fun logicalCount(entries:List<MediaEntry>)=entries.groupBy(::groupKey).values.sumOf{if(it.size==2&&it.any{e->e.video}&&it.any{e->!e.video})1 else it.size}
        fun groupKey(e:MediaEntry):String {
            // SAF trees may contain repeated filenames in different subdirectories.
            val parent=if(e.source=="media")e.folderKey else if(e.source=="manual")e.id else decoded(e.id).substringBeforeLast('/')
            return parent+"|"+e.name.substringBeforeLast('.').lowercase(java.util.Locale.ROOT)
        }
    }
    private fun validated():LinkScope {
        require(mode in setOf("default","all","date","advanced")){"范围参数无效"}
        require(photos||videos){"请选择媒体类型"}
        require(mode!="advanced"||albums.isNotEmpty()){"请选择相册"}
        require(minImageKb in 10..1024){"范围参数无效"}
        if(dateEnabled) {
            if(from.isNotEmpty())LocalDate.parse(from)
            if(to.isNotEmpty())LocalDate.parse(to)
            require(from.isEmpty()||to.isEmpty()||from<=to){"范围参数无效"}
        }
        return this
    }
    fun select(entries:List<MediaEntry>,protectedIds:Set<String> = emptySet()):List<MediaEntry> {
        val zone=ZoneId.systemDefault()
        val start=if(dateEnabled&&from.isNotEmpty())LocalDate.parse(from).atStartOfDay(zone).toInstant().toEpochMilli() else Long.MIN_VALUE
        val end=if(dateEnabled&&to.isNotEmpty())LocalDate.parse(to).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() else Long.MAX_VALUE
        return entries.groupBy(::groupKey).values.flatMap{group ->
            val photo=group.firstOrNull{!it.video}
            val paired=group.size==2&&photo!=null&&group.any{it.video}
            val main=photo?:group.first()
            val folder=when(mode){"advanced"->main.folderKey in albums;"default"->camera(main.folderKey);else->true}
            val path=decoded(main.folderKey).lowercase(java.util.Locale.ROOT)
            val cache=listOf(".thumbnails",".trashed",".trash",".pending","cache","android/data","android/obb").any{path.contains(it)}
            val date=time(main)
            if(!folder||(filterCache&&cache)||(dateEnabled&&(date<=0||date<start||date>=end)))emptyList()
            else if(paired) {if(photos)group else emptyList()}
            else group.filter{e -> (if(e.video)videos else photos)&&
                (e.video||!ignoreSmallImages||e.size<=0||e.size>=minImageKb*1024L||e.id in protectedIds)}
        }
    }
    fun report(revision:Long,entries:List<MediaEntry>,protectedIds:Set<String> = emptySet()):JSONObject {
        val selected=select(entries,protectedIds)
        val albumsJson=JSONArray()
        entries.groupBy{it.folderKey}.forEach{(key,list)->albumsJson.put(JSONObject().put("id",key).put("name",list.first().folderName)
            .put("count",logicalCount(list)).put("bytes",list.sumOf{it.size.coerceAtLeast(0)})
            .put("isDefault",camera(key)))}
        return JSONObject().put("revision",revision).put("timeZone",ZoneId.systemDefault().id).put("albums",albumsJson)
            .put("count",logicalCount(selected)).put("bytes",selected.sumOf{it.size.coerceAtLeast(0)})
            .put("unknownSize",selected.count{it.size<=0}).put("unknownDate",if(dateEnabled)copy(dateEnabled=false).select(entries,protectedIds).groupBy(::groupKey).values.count{time(it.firstOrNull{e->!e.video}?:it.first())<=0} else 0)
    }
    fun summary()=listOf(when(mode){"default"->"相机与截图";"advanced"->"已选 ${albums.size} 个文件夹";else->"全部可访问媒体"},
        if(dateEnabled)"${from.ifEmpty{"不限"}} 至 ${to.ifEmpty{"不限"}}" else "不限日期",
        if(photos&&videos)"照片和视频" else if(photos)"照片（含实况）" else "视频",
        if(ignoreSmallImages)"普通照片小图阈值 $minImageKb KB" else "保留小图",
        if(filterCache)"过滤缓存目录" else "保留缓存目录").joinToString(" · ")
}
