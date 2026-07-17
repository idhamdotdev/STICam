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
import com.sticam.ui.SticamUiState
import com.sticam.ui.SticamViewModel

@Composable
fun FilterTabsOverlay(state: SticamUiState, vm: SticamViewModel) {
    var activeTab by remember { mutableStateOf("AR") }
    
    val arFilters = listOf("None", "Crown", "England")
    val beautyFilters = listOf("None", "Smooth", "Slim Face")
    val lutFilters = listOf("None", "Warm", "Cool", "Grayscale", "Vivid", "Cinematic")
    val funFilters = listOf("None", "TigerPaint", "Skull", "Ironman", "Big Eyes")

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
            Spacer(modifier = Modifier.width(4.dp))
            TabButton("Beauty", activeTab == "Beauty") { activeTab = "Beauty" }
            Spacer(modifier = Modifier.width(4.dp))
            TabButton("Color", activeTab == "Color") { activeTab = "Color" }
            Spacer(modifier = Modifier.width(4.dp))
            TabButton("Fun", activeTab == "Fun") { activeTab = "Fun" }
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
            val currentFilters = when (activeTab) {
                "AR" -> arFilters
                "Beauty" -> beautyFilters
                "Color" -> lutFilters
                "Fun" -> funFilters
                else -> arFilters
            }
            
            items(currentFilters) { filter ->
                val isActive = if (activeTab == "Color") {
                    state.activeLutFilter == filter
                } else {
                    state.activeArFilter == filter
                }
                
                FilterItem(
                    label = filter,
                    isActive = isActive,
                    onClick = {
                        if (activeTab == "Color") {
                            vm.setLutFilter(filter)
                        } else {
                            vm.setArFilter(filter)
                        }
                    }
                )
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
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
