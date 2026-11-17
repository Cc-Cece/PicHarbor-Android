package com.cccece.picharbor

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.TextUnit
import org.json.JSONObject
import java.util.Locale

private val LocalTranslation=staticCompositionLocalOf<(String)->String>{{it}}

private class MobileTranslations(private val table:JSONObject) {
    private data class Template(val expression:Regex,val indices:List<String>,val translated:String,val literalLength:Int)
    private val templates=table.keys().asSequence().mapNotNull { key ->
        val slots=Regex("\\{(\\d+)\\}").findAll(key).toList()
        if(slots.isEmpty())return@mapNotNull null
        val expression=StringBuilder("^")
        var offset=0
        slots.forEach { slot ->
            expression.append(Regex.escape(key.substring(offset,slot.range.first))).append("(.+?)")
            offset=slot.range.last+1
        }
        expression.append(Regex.escape(key.substring(offset))).append("$")
        val literalLength=key.length-slots.sumOf{it.value.length}
        if(literalLength<2)null else Template(Regex(expression.toString(),RegexOption.DOT_MATCHES_ALL),slots.map{it.groupValues[1]},table.getString(key),literalLength)
    }.sortedByDescending{it.literalLength}.toList()
    fun translate(value:String):String {
        if(table.has(value))return table.getString(value)
        for(template in templates) {
            val match=template.expression.matchEntire(value)?:continue
            val values=template.indices.zip(match.groupValues.drop(1)).toMap()
            return Regex("\\{(\\d+)\\}").replace(template.translated){values[it.groupValues[1]]?:it.value}
        }
        return value
    }
}

@Composable
fun MobileLocale(language:String,content:@Composable ()->Unit) {
    val original=LocalContext.current
    val configuration=LocalConfiguration.current
    val system=configuration.locales[0]
    val tag=if(language=="system")system.toLanguageTag() else language
    val locale=Locale.forLanguageTag(tag)
    val localized=remember(configuration,tag){android.content.res.Configuration(configuration).apply{setLocale(locale)}}
    val context=remember(original,localized){original.createConfigurationContext(localized)}
    val translator=remember(tag){
        val tableName=if(tag.startsWith("en"))"en" else if(tag.startsWith("yue")||tag.contains("HK",true))"yue-Hant-HK" else ""
        val table=if(tableName.isBlank())JSONObject() else original.assets.open("localization/$tableName.json").bufferedReader().use{JSONObject(it.readText())}
        val translations=MobileTranslations(table)
        val translate:(String)->String={value->translations.translate(value)}
        translate
    }
    CompositionLocalProvider(LocalContext provides context,LocalConfiguration provides localized,LocalTranslation provides translator,content=content)
}

@Composable
fun LText(text:String,modifier:Modifier=Modifier,color:Color=Color.Unspecified,fontSize:TextUnit=TextUnit.Unspecified,
    fontStyle:FontStyle?=null,fontWeight:FontWeight?=null,fontFamily:FontFamily?=null,letterSpacing:TextUnit=TextUnit.Unspecified,
    textDecoration:TextDecoration?=null,textAlign:TextAlign?=null,lineHeight:TextUnit=TextUnit.Unspecified,
    overflow:TextOverflow=TextOverflow.Clip,softWrap:Boolean=true,maxLines:Int=Int.MAX_VALUE,minLines:Int=1,
    style:TextStyle=LocalTextStyle.current) {
    androidx.compose.material3.Text(text=LocalTranslation.current(text),modifier=modifier,color=color,fontSize=fontSize,fontStyle=fontStyle,
        fontWeight=fontWeight,fontFamily=fontFamily,letterSpacing=letterSpacing,textDecoration=textDecoration,textAlign=textAlign,
        lineHeight=lineHeight,overflow=overflow,softWrap=softWrap,maxLines=maxLines,minLines=minLines,style=style)
}
