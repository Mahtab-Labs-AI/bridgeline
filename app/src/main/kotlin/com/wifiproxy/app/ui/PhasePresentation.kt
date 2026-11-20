package com.wifiproxy.app.ui

import androidx.compose.ui.graphics.Color
import com.wifiproxy.app.session.SessionPhase
import com.wifiproxy.app.ui.theme.PhaseColors

fun SessionPhase.color(): Color = when (this) {
    SessionPhase.IDLE -> PhaseColors.Idle
    SessionPhase.STARTING -> PhaseColors.Starting
    SessionPhase.RUNNING -> PhaseColors.Running
    SessionPhase.DEGRADED -> PhaseColors.Degraded
    SessionPhase.STOPPING -> PhaseColors.Stopping
}

fun SessionPhase.label(): String = when (this) {
    SessionPhase.IDLE -> "Stopped"
    SessionPhase.STARTING -> "Starting…"
    SessionPhase.RUNNING -> "Running"
    SessionPhase.DEGRADED -> "Degraded"
    SessionPhase.STOPPING -> "Stopping…"
}
