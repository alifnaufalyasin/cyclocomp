package com.aliven.cyclocomp.ui

import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalContext
import com.aliven.cyclocomp.core.TileTemplate
import com.aliven.cyclocomp.map.MapSettings
import com.aliven.cyclocomp.nav.NavSettings
import com.aliven.cyclocomp.nav.OsrmAddress
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Choose which data fields appear on each swipeable ride-screen page. */
@Composable
fun PagesEditor(initial: List<List<DataField>>, onSave: (List<List<DataField>>) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var pages by remember { mutableStateOf(initial) }
    var showMap by remember { mutableStateOf(MapSettings.showMapPage(context)) }
    var tileUrl by remember { mutableStateOf(MapSettings.tileTemplate(context)) }
    val tileUrlBad = tileUrl.isNotBlank() && !TileTemplate.isValid(tileUrl)
    var osrmUrl by remember { mutableStateOf(NavSettings.osrmUrl(context)) }
    val osrmBad = osrmUrl.isNotBlank() && OsrmAddress.normalize(osrmUrl) == null
    var avoidToll by remember { mutableStateOf(NavSettings.avoidToll(context)) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(
                enabled = !tileUrlBad && !osrmBad,
                onClick = {
                    MapSettings.save(context, showMap, tileUrl)
                    NavSettings.saveOsrmUrl(context, OsrmAddress.normalize(osrmUrl) ?: "")
                    NavSettings.saveAvoidToll(context, avoidToll)
                    onSave(pages.filter { it.isNotEmpty() }.ifEmpty { Pages.DEFAULT })
                },
            ) { Text("Save") }
        }
        Text("Up to ${Pages.MAX_FIELDS_PER_PAGE} fields per page. Empty pages are dropped.", fontSize = 14.sp)

        Row(Modifier.fillMaxWidth().clickable { showMap = !showMap }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = showMap, onCheckedChange = { showMap = it })
            Text("Show the map as the last page")
        }
        OutlinedTextField(
            value = tileUrl, onValueChange = { tileUrl = it }, singleLine = true,
            label = { Text("Own tile server (optional)") },
            placeholder = { Text("https://…/{z}/{x}/{y}.png") },
            supportingText = {
                Text(
                    if (tileUrlBad) "Needs https:// and {z}, {x}, {y}"
                    else "Empty = OpenStreetMap standard tiles (fine for personal use; no bulk offline download)"
                )
            },
            isError = tileUrlBad,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = osrmUrl, onValueChange = { osrmUrl = it }, singleLine = true,
            label = { Text("Routing server (OSRM, for navigation)") },
            placeholder = { Text("https://…") },
            supportingText = {
                Text(
                    if (osrmBad) "Needs an https:// address"
                    else "Long-press the map to pick a destination. A bicycle profile gives the best cycling routes."
                )
            },
            isError = osrmBad,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().clickable { avoidToll = !avoidToll }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = avoidToll, onCheckedChange = { avoidToll = it })
            Text("Avoid toll roads (for a motorcycle or car routing profile)")
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(pages) { pageIndex, page ->
                Text("Page ${pageIndex + 1}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                DataField.values().forEach { field ->
                    val checked = field in page
                    val canAdd = checked || page.size < Pages.MAX_FIELDS_PER_PAGE
                    val toggle = {
                        if (canAdd) {
                            pages = pages.mapIndexed { i, p ->
                                if (i != pageIndex) p else if (checked) p - field else p + field
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { toggle() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { toggle() }, enabled = canAdd)
                        Text(field.label)
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pages = pages + listOf(listOf(DataField.SPEED)) }, modifier = Modifier.weight(1f)) {
                Text("Add page")
            }
            OutlinedButton(
                onClick = { pages = pages.dropLast(1) },
                enabled = pages.size > 1,
                modifier = Modifier.weight(1f),
            ) { Text("Remove last") }
        }
    }
}
