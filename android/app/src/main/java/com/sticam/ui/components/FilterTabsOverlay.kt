package com.sticam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
@Composable
fun FilterTabsOverlay(
    activeArFilter: String,
    activeLutFilter: String,
    onArFilterSelected: (String) -> Unit,
    onLutFilterSelected: (String) -> Unit,
) {
    var activeTab by remember { mutableStateOf("AR") }
    
    val arFilters = remember { listOf("None", "Crown") }
    val lutFilters = remember { listOf("None") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Tab Headers
        Row(
            modifier = Modifier
                .background(Color(0xFF0C1328).copy(alpha = 0.8f), RoundedCornerShape(20.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            TabButton("AR", activeTab == "AR") { activeTab = "AR" }
            Spacer(modifier = Modifier.width(8.dp))
            TabButton("Color", activeTab == "Color") { activeTab = "Color" }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Horizontal Strip
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0C1328).copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            if (activeTab == "AR") {
                items(arFilters, key = { it }) { filter ->
                    FilterItem(
                        label = filter,
                        isActive = activeArFilter == filter,
                        onClick = { onArFilterSelected(filter) }
                    )
                }
            } else {
                items(lutFilters, key = { it }) { filter ->
                    FilterItem(
                        label = filter,
                        isActive = activeLutFilter == filter,
                        onClick = { onLutFilterSelected(filter) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TabButton(label: String, isActive: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(
                if (isActive) Color(0xFF1ECC91) else Color.Transparent,
                RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 24.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isActive) Color(0xFF0C1328) else Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun FilterItem(label: String, isActive: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(
                if (isActive) Color(0xFF1ECC91).copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f),
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isActive) Color(0xFF1ECC91) else Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
    }
}
