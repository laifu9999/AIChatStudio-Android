package com.lele.mobipaint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.lightColorScheme

val Brand = Color(0xFF3B5BDB)
val UserBlue = Color(0xFF3B5BDB)
val InkDark = Color(0xFF2B2F3A)
val WarnOrange = Color(0xFFE08A00)

@Composable
fun MobipaintTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Brand,
            background = Color(0xFFF6F7FB),
            surface = Color.White
        ),
        content = content
    )
}

/** 顶栏：返回 + 标题 + 右侧操作区。 */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        if (onBack != null) {
            TextButtonSmall("←", onBack)
        }
        Text(
            title,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            fontSize = 17.sp, color = InkDark,
            maxLines = 1
        )
        actions()
    }
}

@Composable
fun TextButtonSmall(text: String, onClick: () -> Unit, color: Color = Brand) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .background(color.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

/** 聊天气泡：透明背景，用户消息蓝色带「我：」前缀，AI 深色全宽自适应（墨笔风格）。 */
@Composable
fun ChatBubble(role: String, text: String) {
    val isUser = role == "user"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        if (isUser) {
            Text(
                buildString { append("我："); append(text) },
                color = UserBlue,
                style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
            )
        } else {
            Text(
                text,
                color = InkDark,
                style = TextStyle(fontSize = 15.sp, lineHeight = 23.sp)
            )
        }
    }
}

/** 状态行：错误红 / 提示橙。 */
@Composable
fun StatusLine(error: String?, info: String?) {
    if (error != null) {
        Text(
            error,
            color = Color(0xFFC62828),
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
        )
    }
    if (info != null && info.isNotEmpty()) {
        Text(
            info,
            color = WarnOrange,
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
        )
    }
}
