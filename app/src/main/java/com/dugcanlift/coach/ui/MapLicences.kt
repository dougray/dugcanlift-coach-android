package com.dugcanlift.coach.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dugcanlift.coach.ui.theme.dclTextButtonColors

/**
 * The licences and credit for what street maps are drawn with. The wording is the kit's own `NOTICE`
 * (dugcanlift-kit-android, `liftmap`), which every build carries: MapLibre Native's BSD-2 text must
 * travel with the app, Noto Sans is under the SIL OFL 1.1, and the street data is
 * OpenStreetMap's (ODbL) as served by OpenFreeMap. Never edit it here alone.
 */
object MapLicences {
    const val credit = "© OpenStreetMap contributors · OpenFreeMap"

    val text: String = """
Street maps

$credit
Street data © OpenStreetMap contributors, under the Open Database License (ODbL) 1.0 - https://www.openstreetmap.org/copyright
Vector tiles from OpenFreeMap

Noto Sans
The map's street names are set in Noto Sans, under the SIL Open Font License 1.1 - https://openfontlicense.org

MapLibre Native for Android (org.maplibre.gl:android-sdk-opengl)
BSD-2-Clause

Copyright (c) 2021 MapLibre contributors

Copyright (c) 2018-2021 MapTiler.com

Copyright (c) 2014-2020 Mapbox

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
* Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

Other libraries street maps use, each under its own license
SQLite, through androidx.sqlite:sqlite-bundled: public domain.
androidx.sqlite, Jetpack Compose, AndroidX Lifecycle, Square's OK HTTP library, kotlinx.coroutines: Apache-2.0.
""".trim()
}

/** A "Map licences" line on the Connect screen that opens [MapLicences.text]. */
@Composable
fun MapLicencesSection() {
    var open by rememberSaveable { mutableStateOf(false) }
    Spacer(modifier = Modifier.height(24.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))
    Text("Map credits", style = MaterialTheme.typography.titleMedium)
    Text(
        MapLicences.credit,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    TextButton(onClick = { open = true }, colors = dclTextButtonColors()) { Text("Map licences") }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Map licences") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(MapLicences.text, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { open = false }, colors = dclTextButtonColors()) { Text("Close") } },
        )
    }
}
