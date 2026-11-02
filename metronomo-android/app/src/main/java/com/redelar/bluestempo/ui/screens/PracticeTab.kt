package com.redelar.bluestempo.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.redelar.bluestempo.BuildConfig
import com.redelar.bluestempo.model.AppSettings
import com.redelar.bluestempo.ui.MetronomeActions
import com.redelar.bluestempo.ui.components.ChoiceChips
import com.redelar.bluestempo.ui.components.Section
import com.redelar.bluestempo.ui.components.SettingSwitch
import com.redelar.bluestempo.ui.components.Stepper

@Composable
fun PracticeTab(settings: AppSettings, vm: MetronomeActions) {
    TabColumn {
        Section("Contagem inicial", subtitle = "Compassos de clique antes de começar (a levada entra depois).") {
            ChoiceChips(
                options = listOf(0, 1, 2, 4),
                selected = settings.countInBars,
                label = { if (it == 0) "Sem contagem" else "$it comp." },
                onSelect = vm::setCountIn,
            )
        }

        Section("Treinador de andamento", subtitle = "Acelera (ou desacelera) automaticamente enquanto você toca.") {
            SettingSwitch("Ativar", settings.trainerEnabled, vm::setTrainerEnabled)
            Stepper(
                "Andamento alvo",
                settings.trainerTargetBpm,
                vm::setTrainerTarget,
                AppSettings.MIN_BPM..AppSettings.MAX_BPM,
            )
            Stepper("Mudar (BPM)", settings.trainerStepBpm, vm::setTrainerStep, 1..20, valueText = "${settings.trainerStepBpm}")
            Stepper(
                "A cada (compassos)",
                settings.trainerEveryBars,
                vm::setTrainerEvery,
                1..32,
            )
            val direction = if (settings.trainerTargetBpm >= settings.bpm) "sobe" else "desce"
            Text(
                "Começa em ${settings.bpm} BPM e $direction ${settings.trainerStepBpm} BPM a cada " +
                    "${settings.trainerEveryBars} compasso(s) até ${settings.trainerTargetBpm} BPM.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section(
            "Compassos em silêncio",
            subtitle = "Treine o tempo interno: o som some por alguns compassos e volta — você deve continuar no tempo.",
        ) {
            SettingSwitch("Ativar", settings.gapEnabled, vm::setGapEnabled)
            Stepper("Tocar (compassos)", settings.gapPlayBars, vm::setGapPlay, 1..16)
            Stepper("Silenciar (compassos)", settings.gapMuteBars, vm::setGapMute, 1..16)
        }

        Section("Timer de estudo", subtitle = "Para automaticamente depois do tempo escolhido.") {
            ChoiceChips(
                options = listOf(0, 5, 10, 15, 20, 30, 45, 60),
                selected = settings.timerMinutes,
                label = { if (it == 0) "Desligado" else "$it min" },
                onSelect = vm::setTimer,
            )
        }

        Section("Opções") {
            SettingSwitch(
                "Tocar em segundo plano",
                settings.backgroundPlayback,
                vm::setBackgroundPlayback,
                subtitle = "Continua com a tela apagada (mostra uma notificação com botão Parar).",
            )
            SettingSwitch(
                "Tocar junto com outros apps",
                settings.mixWithOtherApps,
                vm::setMixWithOtherApps,
                subtitle = "Não pausa a música de outros apps (para tocar junto com gravações). " +
                    "Desligado, o BluesTempo pausa os outros apps ao começar.",
            )
            SettingSwitch("Manter a tela ligada", settings.keepScreenOn, vm::setKeepScreenOn, subtitle = "Enquanto estiver tocando.")
            SettingSwitch("Piscar no tempo 1", settings.flashOnDownbeat, vm::setFlash, subtitle = "Referência visual para palco e ensaio.")
            SettingSwitch(
                "Usar andamento sugerido",
                settings.useGrooveTempo,
                vm::setUseGrooveTempo,
                subtitle = "Ao escolher uma levada, ajusta o BPM para o andamento típico do estilo.",
            )
        }

        Section("Sobre") {
            Text(
                "BluesTempo ${BuildConfig.VERSION_NAME}\n" +
                    "Tempo gerado com precisão de amostra — não \"escorrega\" mesmo depois de horas.\n\n" +
                    "Instrumentos reais (domínio público, CC0):\n" +
                    "• Virtuosity Drums — Versilian Studios & Karoryfer Samples\n" +
                    "• Big Rusty Drums (vassourinhas) — Karoryfer Samples\n" +
                    "• Black And Blue Basses (baixo elétrico) — Karoryfer Samples\n" +
                    "• Meatbass (contrabaixo) — Karoryfer Samples\n" +
                    "• VCSL (percussão) — Versilian Community Sample Library",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
