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
