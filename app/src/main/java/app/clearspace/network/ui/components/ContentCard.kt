package app.clearspace.network.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.clearspace.network.storage.db.entity.ContentUnit
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ContentCard(unit: ContentUnit, onReportContent: (ContentUnit, String) -> Unit = { _, _ -> }) {
    // Premium styling applied to Card
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(unit.title, style = MaterialTheme.typography.titleLarge)
            Text("By ${unit.authorAlias}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                unit.body, 
                style = MaterialTheme.typography.bodyMedium, 
                maxLines = 3, 
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(), 
                horizontalArrangement = Arrangement.SpaceBetween, 
                verticalArrangement = Arrangement.Center
            ) {
                val format = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                Text(format.format(Date(unit.timestamp)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                Text("Reputation: ${unit.likeTokens.size - unit.dislikeTokens.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                
                var showReportDialog by remember { mutableStateOf(false) }
                var selectedReason by remember { mutableStateOf("Child Safety / CSAM / CSAE") }
                
                TextButton(onClick = { showReportDialog = true }) {
                    Text("Report", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                
                if (showReportDialog) {
                    AlertDialog(
                        onDismissRequest = { showReportDialog = false },
                        title = { Text("Report Content") },
                        text = {
                            Column {
                                Text("Please select a reason for reporting this content:", style = MaterialTheme.typography.bodyMedium)
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                val reasons = listOf(
                                    "Child Safety / CSAM / CSAE",
                                    "Harassment or Bullying",
                                    "Violence or Terrorism",
                                    "Hate Speech",
                                    "Other Community Guideline Violation"
                                )
                                
                                reasons.forEach { reason ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { selectedReason = reason }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = selectedReason == reason,
                                            onClick = { selectedReason = reason }
                                        )
                                        Text(reason, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Note: Reports are uploaded securely to the developer database to comply with Google Play Developer policies. Illegal content, especially CSAM, will be reported to competent national and regional authorities.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = { 
                                    onReportContent(unit, selectedReason)
                                    showReportDialog = false 
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Submit Report")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showReportDialog = false }) { Text("Cancel") }
                        }
                    )
                }
            }
        }
    }
}
