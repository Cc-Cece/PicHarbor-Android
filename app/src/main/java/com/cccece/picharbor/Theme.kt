package com.cccece.picharbor

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

@Composable
fun PicHarborTheme(settings:HarborSettings,content:@Composable ()->Unit) {
    val dark=when(settings.theme){"dark"->true;"light"->false;else->isSystemInDarkTheme()}
    val scheme=if(settings.palette=="system"&&Build.VERSION.SDK_INT>=31) {
        if(dark)dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else {
        val colors=when(settings.palette){
            "sage"->arrayOf(Color(0xFF386652),Color(0xFFC6ECD7),Color(0xFF143C2A),Color(0xFFA2D2B8),Color(0xFF315440))
            "peach"->arrayOf(Color(0xFF8C503B),Color(0xFFFFDBCB),Color(0xFF4D2619),Color(0xFFF0B89C),Color(0xFF6A3E2C))
            else->arrayOf(Color(0xFF67518E),Color(0xFFEADDFF),Color(0xFF281341),Color(0xFFD1BCF3),Color(0xFF4F3C68))
        }
        if(dark)darkColorScheme(primary=colors[3],onPrimary=colors[2],primaryContainer=colors[4],onPrimaryContainer=colors[1],
            background=Color(0xFF151218),surface=Color(0xFF151218),surfaceContainer=Color(0xFF211C25),surfaceContainerLow=Color(0xFF1F1B23),surfaceContainerHigh=Color(0xFF2B2630))
        else lightColorScheme(primary=colors[0],onPrimary=Color.White,primaryContainer=colors[1],onPrimaryContainer=colors[2],
            background=Color(0xFFFDF8FF),surface=Color(0xFFFDF8FF),surfaceContainer=Color(0xFFF1EAF5),surfaceContainerLow=Color(0xFFF7F1FA),surfaceContainerHigh=Color(0xFFEEE7F2))
    }
    val context=LocalContext.current
    val view=LocalView.current
    SideEffect {
        (context as? android.app.Activity)?.window?.let { window ->
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(scheme.background.toArgb()))
            androidx.core.view.WindowCompat.getInsetsController(window,view).apply {
                isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark
            }
        }
    }
    MaterialTheme(colorScheme=scheme,shapes=Shapes(extraSmall=RoundedCornerShape(8.dp),small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(20.dp),large=RoundedCornerShape(26.dp),extraLarge=RoundedCornerShape(30.dp)),content=content)
}
