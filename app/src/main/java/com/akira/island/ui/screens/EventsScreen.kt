package com.akira.island.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.AirplanemodeInactive
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.PowerOff
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.UsbOff
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.akira.island.core.tr
import com.akira.island.data.IslandSettings
import com.akira.island.data.SettingsRepo
import com.akira.island.ui.Group
import com.akira.island.ui.GroupSlider
import com.akira.island.ui.GroupToggle
import com.akira.island.ui.Page
import com.akira.island.ui.Pos
import com.akira.island.ui.SectionHeader
import com.akira.island.ui.Tile

/** «События»: короткие плашки о том, что произошло, — по разделам. */
@Composable
fun EventsScreen(onBack: () -> Unit) {
    val s by SettingsRepo.state.collectAsState()
    fun set(block: IslandSettings.() -> IslandSettings) = SettingsRepo.update(block)

    Page(tr("События", "Events"), onBack) {
        SectionHeader(tr("Батарея", "Battery"))
        Group {
            GroupToggle(Pos.First, Icons.Rounded.Bolt, tr("Зарядка началась", "Charging started"), null,
                s.evChargeStart, color = Tile.Green) { set { copy(evChargeStart = it) } }
            GroupToggle(Pos.Middle, Icons.Rounded.PowerOff, tr("Зарядка остановлена", "Charging stopped"), null,
                s.evChargeStop, color = Tile.Gray) { set { copy(evChargeStop = it) } }
            GroupToggle(if (s.evLowBattery) Pos.Middle else Pos.Last, Icons.Rounded.BatteryAlert, tr("Низкий заряд", "Low battery"),
                tr("Когда заряд падает до ${s.batteryThreshold}%", "When the battery drops to ${s.batteryThreshold}%"),
                s.evLowBattery, color = Tile.Red) { set { copy(evLowBattery = it) } }
            if (s.evLowBattery) {
                GroupSlider(
                    Pos.Last, Icons.Rounded.BatteryAlert, tr("Порог низкого заряда", "Low battery threshold"),
                    value = s.batteryThreshold.toFloat(), range = 5f..50f, steps = 8,
                    label = "${s.batteryThreshold}%", color = Tile.Red,
                ) { v -> set { copy(batteryThreshold = v.toInt()) } }
            }
        }

        GroupToggle(Pos.Single, Icons.Rounded.BatterySaver, tr("Энергосбережение", "Power saving"),
            tr("Когда Android включает или выключает экономию заряда", "When Android turns battery saver on or off"),
            s.evPowerSave, color = Tile.Green) { set { copy(evPowerSave = it) } }

        SectionHeader(tr("Подключения", "Connections"))
        Group {
            GroupToggle(Pos.First, Icons.Rounded.Wifi, tr("Wi-Fi подключён", "Wi-Fi connected"), null,
                s.evWifiOn, color = Tile.Blue) { set { copy(evWifiOn = it) } }
            GroupToggle(Pos.Middle, Icons.Rounded.WifiOff, tr("Wi-Fi отключён", "Wi-Fi disconnected"), null,
                s.evWifiOff, color = Tile.Gray) { set { copy(evWifiOff = it) } }
            GroupToggle(Pos.Middle, Icons.Rounded.BluetoothConnected, tr("Наушники подключены", "Headphones connected"),
                tr("По Bluetooth, с зарядом, если он известен", "Over Bluetooth, with the charge if it's known"),
                s.evHeadsetOn, color = Tile.Indigo) { set { copy(evHeadsetOn = it) } }
            GroupToggle(Pos.Middle, Icons.Rounded.BluetoothDisabled, tr("Наушники отключены", "Headphones disconnected"), null,
                s.evHeadsetOff, color = Tile.Gray) { set { copy(evHeadsetOff = it) } }
            GroupToggle(Pos.Middle, Icons.Rounded.Usb, tr("USB-устройство подключено", "USB device connected"), null,
                s.evUsbOn, color = Tile.Teal) { set { copy(evUsbOn = it) } }
            GroupToggle(Pos.Last, Icons.Rounded.UsbOff, tr("USB-устройство отключено", "USB device disconnected"), null,
                s.evUsbOff, color = Tile.Gray) { set { copy(evUsbOff = it) } }
        }

        SectionHeader(tr("Режимы", "Modes"))
        Group {
            GroupToggle(Pos.First, Icons.Rounded.AirplanemodeActive, tr("Режим полёта включён", "Airplane mode on"), null,
                s.evAirplaneOn, color = Tile.Orange) { set { copy(evAirplaneOn = it) } }
            GroupToggle(Pos.Middle, Icons.Rounded.AirplanemodeInactive, tr("Режим полёта выключен", "Airplane mode off"), null,
                s.evAirplaneOff, color = Tile.Gray) { set { copy(evAirplaneOff = it) } }
            GroupToggle(Pos.Middle, Icons.Rounded.DoNotDisturbOn, tr("Не беспокоить", "Do Not Disturb"),
                tr("Луна и состояние при переключении", "A moon and the state when it changes"),
                s.evDnd, color = Tile.Indigo) { set { copy(evDnd = it) } }
            GroupToggle(Pos.Middle, Icons.Rounded.Vibration, tr("Режим громкости", "Ring mode"),
                tr("Звонок, вибрация или без звука", "Ring, vibrate or silent"),
                s.evRinger, color = Tile.Pink) { set { copy(evRinger = it) } }
            GroupToggle(Pos.Last, Icons.Rounded.BrightnessAuto, tr("Режим яркости", "Brightness mode"),
                tr("Когда включают или выключают адаптивную яркость", "When adaptive brightness is turned on or off"),
                s.evBrightness, color = Tile.Yellow) { set { copy(evBrightness = it) } }
        }

        SectionHeader(tr("Оплата", "Payments"))
        Group {
            GroupToggle(Pos.First, Icons.Rounded.Nfc, tr("Приложите к терминалу", "Hold near reader"),
                tr("Когда на экране оплата телефоном — Mir Pay, T-Pay, SberPay, Яндекс Пэй и другие. Держится, пока не заплатите или не выйдете из приложения",
                    "When a phone payment is on screen — Mir Pay, T-Pay, SberPay, Yandex Pay and others. Stays until you pay or leave the app"),
                s.evPayReady, color = Tile.Blue) { set { copy(evPayReady = it) } }
            GroupToggle(Pos.Middle, Icons.Rounded.AccountBalance, tr("Подсказка при открытии банка", "Hint when opening a bank"),
                tr("«К терминалу» сразу, как открыли банк или кошелёк, а не только на экране оплаты",
                    "Hold near reader as soon as you open a bank or wallet app, not only on the payment screen"),
                s.payHintOnOpen, color = Tile.Gray) { set { copy(payHintOnOpen = it) } }
            GroupToggle(Pos.Last, Icons.Rounded.CheckCircle, tr("Оплачено", "Paid"),
                tr("Галочка и сумма — с экрана кошелька, из уведомления или СМС банка",
                    "A check mark and the amount — from the wallet screen, a notification or a bank SMS"),
                s.evPaySuccess, color = Tile.Green) { set { copy(evPaySuccess = it) } }
        }

        SectionHeader(tr("Другое", "Other"))
        Group {
            GroupToggle(Pos.First, Icons.Rounded.Alarm, tr("Будильник поставлен", "Alarm set"),
                tr("Время подъёма и сколько осталось спать", "Wake-up time and how long you can sleep"),
                s.evAlarmSet, color = Tile.Orange) { set { copy(evAlarmSet = it) } }
            GroupToggle(Pos.Middle, Icons.Rounded.Lock, tr("Замок на экране блокировки", "Lock on the lock screen"),
                tr("Пока телефон заблокирован, в капсуле замок", "While the phone is locked, the capsule shows a lock"),
                s.evLock, color = Tile.Indigo) { set { copy(evLock = it) } }
            GroupToggle(Pos.Last, Icons.Rounded.LockOpen, tr("Разблокировка устройства", "Device unlock"),
                tr("Замок открывается, когда вы разблокируете телефон", "A lock opens when you unlock the phone"),
                s.evUnlock, color = Tile.Purple) { set { copy(evUnlock = it) } }
        }
    }
}
