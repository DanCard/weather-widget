package com.weatherwidget.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatherwidget.data.local.desktop.DesktopLogEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun LogList(logs: List<DesktopLogEntity>, modifier: Modifier = Modifier) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("MMM d, h:mm:ss a").withZone(ZoneId.systemDefault()) }

    LazyColumn(modifier = modifier.fillMaxSize().padding(8.dp)) {
        items(logs) { log ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        timeFormatter.format(Instant.ofEpochMilli(log.timestamp)),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = ObsStyle.accent
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        log.tag,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .background(
                                if (log.tag.contains("FAIL")) Color.Red.copy(alpha = 0.2f) else Color.Green.copy(alpha = 0.2f),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                Text(log.message, fontSize = 17.sp, modifier = Modifier.padding(top = 2.dp))
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp), color = ObsStyle.divider)
            }
        }

        if (logs.isEmpty()) {
            item {
                Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No logs found", fontSize = 18.sp, color = ObsStyle.textSecondary)
                }
            }
        }
    }
}
