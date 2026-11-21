package com.wifiproxy.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.ui.graphics.vector.ImageVector
import com.wifiproxy.app.session.ClientPlatform

/** The device icon shown for a slot on Home and Devices. */
fun ClientPlatform.icon(): ImageVector = when (this) {
    ClientPlatform.IPHONE -> Icons.Filled.PhoneIphone
    ClientPlatform.ANDROID -> Icons.Filled.PhoneAndroid
    ClientPlatform.LAPTOP -> Icons.Filled.Laptop
    ClientPlatform.UNKNOWN -> Icons.Filled.Devices
}
