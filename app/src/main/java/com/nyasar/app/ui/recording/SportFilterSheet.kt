package com.nyasar.app.ui.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nyasar.app.recording.SportCategory
import com.nyasar.app.recording.SportType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SportFilterSheet(
    selectedSport: SportType,
    onSelectSport: (SportType) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val topSports = listOf(SportType.TRAIL_RUN, SportType.HIKE)
    
    val filteredSports = remember(searchQuery) {
        if (searchQuery.isBlank()) {
            SportType.entries
        } else {
            SportType.entries.filter { 
                it.label.contains(searchQuery, ignoreCase = true) 
            }
        }
    }
    
    val groupedSports = remember(filteredSports) {
        filteredSports.groupBy { it.category }
    }
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1D20),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Filter by Sport",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Tutup",
                        tint = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
            
            // Search box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search", color = Color.White.copy(alpha = 0.5f)) },
                leadingIcon = { 
                    Icon(
                        Icons.Default.Search, 
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f)
                    ) 
                },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFFC5200),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                singleLine = true
            )
            
            // Your Top Sports section
            if (searchQuery.isBlank()) {
                Text(
                    "Your Top Sports",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 12.dp)
                )
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    topSports.forEach { sport ->
                        TopSportItem(
                            sport = sport,
                            isSelected = sport == selectedSport,
                            onClick = { onSelectSport(sport) }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            // Categorized sports list
            LazyColumn(
                modifier = Modifier.heightIn(max = 400.dp)
            ) {
                groupedSports.forEach { (category, sports) ->
                    item {
                        Text(
                            category.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                        )
                    }
                    
                    items(sports) { sport ->
                        SportListItem(
                            sport = sport,
                            isSelected = sport == selectedSport,
                            onClick = { onSelectSport(sport) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopSportItem(
    sport: SportType,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    if (isSelected) Color(0xFFFC5200)
                    else Color.White.copy(alpha = 0.1f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                sport.icon,
                contentDescription = sport.label,
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
            
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFC5200)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Text(
            sport.label,
            style = MaterialTheme.typography.bodySmall,
            color = if (isSelected) Color(0xFFFC5200) else Color.White
        )
    }
}

@Composable
private fun SportListItem(
    sport: SportType,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            sport.icon,
            contentDescription = sport.label,
            tint = if (isSelected) Color(0xFFFC5200) else Color.White,
            modifier = Modifier.size(24.dp)
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Text(
            sport.label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) Color(0xFFFC5200) else Color.White,
            modifier = Modifier.weight(1f)
        )
        
        if (isSelected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Dipilih",
                tint = Color(0xFFFC5200),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
