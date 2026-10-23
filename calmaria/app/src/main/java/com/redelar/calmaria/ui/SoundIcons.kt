package com.redelar.calmaria.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.BedroomBaby
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.Cottage
import androidx.compose.material.icons.outlined.Cyclone
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Dry
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.EnergySavingsLeaf
import androidx.compose.material.icons.outlined.Festival
import androidx.compose.material.icons.outlined.Fireplace
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.FlutterDash
import androidx.compose.material.icons.outlined.Forest
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.Grass
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.Hvac
import androidx.compose.material.icons.outlined.Kayaking
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.LocalLaundryService
import androidx.compose.material.icons.outlined.LocationCity
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.NoiseAware
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.PestControl
import androidx.compose.material.icons.outlined.Piano
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RiceBowl
import androidx.compose.material.icons.outlined.Roofing
import androidx.compose.material.icons.outlined.ScubaDiving
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Shower
import androidx.compose.material.icons.outlined.Thunderstorm
import androidx.compose.material.icons.outlined.Toys
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material.icons.outlined.Umbrella
import androidx.compose.material.icons.outlined.Water
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material.icons.outlined.Window
import androidx.compose.ui.graphics.vector.ImageVector

fun soundIcon(id: String): ImageVector = when (id) {
    "rain_light" -> Icons.Outlined.Grain
    "rain_medium" -> Icons.Outlined.WaterDrop
    "rain_heavy" -> Icons.Outlined.Shower
    "rain_thunder" -> Icons.Outlined.Thunderstorm
    "rain_umbrella" -> Icons.Outlined.Umbrella
    "rain_roof" -> Icons.Outlined.Roofing
    "rain_window" -> Icons.Outlined.Window
    "rain_leaves" -> Icons.Outlined.Eco
    "rain_tent" -> Icons.Outlined.Festival
    "water_drops" -> Icons.Outlined.Opacity
    "ocean" -> Icons.Outlined.Waves
    "shore" -> Icons.Outlined.BeachAccess
    "stream" -> Icons.Outlined.Water
    "river" -> Icons.Outlined.Kayaking
    "waterfall" -> Icons.Outlined.Landscape
    "underwater" -> Icons.Outlined.ScubaDiving
    "wind" -> Icons.Outlined.Air
    "wind_trees" -> Icons.Outlined.Park
    "howling_wind" -> Icons.Outlined.Cyclone
    "blizzard" -> Icons.Outlined.AcUnit
    "thunder" -> Icons.Outlined.Bolt
    "forest_birds" -> Icons.Outlined.FlutterDash
    "morning_birds" -> Icons.Outlined.WbTwilight
    "doves" -> Icons.Outlined.Cottage
    "owl" -> Icons.Outlined.NightsStay
    "crickets" -> Icons.Outlined.PestControl
    "cicadas" -> Icons.Outlined.BugReport
    "frogs" -> Icons.Outlined.Grass
    "campfire" -> Icons.Outlined.LocalFireDepartment
    "night_forest" -> Icons.Outlined.Forest
    "city" -> Icons.Outlined.LocationCity
    "train" -> Icons.Outlined.Train
    "airplane" -> Icons.Outlined.Flight
    "car" -> Icons.Outlined.DirectionsCar
    "cafe" -> Icons.Outlined.LocalCafe
    "keyboard" -> Icons.Outlined.Keyboard
    "clock" -> Icons.Outlined.Schedule
    "fan" -> Icons.Outlined.Toys
    "air_conditioner" -> Icons.Outlined.Hvac
    "washing_machine" -> Icons.Outlined.LocalLaundryService
    "hair_dryer" -> Icons.Outlined.Dry
    "fireplace" -> Icons.Outlined.Fireplace
    "white_noise" -> Icons.Outlined.GraphicEq
    "pink_noise" -> Icons.Outlined.NoiseAware
    "brown_noise" -> Icons.Outlined.BlurOn
    "deep_brown" -> Icons.Outlined.Bedtime
    "green_noise" -> Icons.Outlined.EnergySavingsLeaf
    "heartbeat" -> Icons.Outlined.MonitorHeart
    "womb" -> Icons.Outlined.BedroomBaby
    "shush" -> Icons.Outlined.ChildCare
    "singing_bowl" -> Icons.Outlined.RiceBowl
    "wind_chimes" -> Icons.Outlined.NotificationsNone
    "om" -> Icons.Outlined.SelfImprovement
    "pad" -> Icons.Outlined.LibraryMusic
    "kalimba" -> Icons.Outlined.MusicNote
    "piano" -> Icons.Outlined.Piano
    "music_box" -> Icons.AutoMirrored.Outlined.QueueMusic
    "binaural_delta" -> Icons.Outlined.Headphones
    "binaural_theta" -> Icons.Outlined.Psychology
    "binaural_alpha" -> Icons.Outlined.Hearing
    else -> Icons.Outlined.MusicNote
}
