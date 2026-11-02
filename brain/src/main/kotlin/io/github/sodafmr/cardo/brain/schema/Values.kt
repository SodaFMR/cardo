package io.github.sodafmr.cardo.brain.schema

// All time types are java.time; "now" always comes from an injected java.time.Clock.

enum class NumberLabel { MOBILE, HOME, WORK, OTHER }

/** REDIAL = last outgoing, CALL_BACK = last incoming or missed. */
enum class CallLastKind { REDIAL, CALL_BACK }

enum class MessagingApp { SMS, WHATSAPP, TELEGRAM, SIGNAL, ANY }

sealed interface Recipient {
    data class Contact(val name: String) : Recipient

    data class Number(val digits: String) : Recipient
}

/** Empty = one-off; all 7 = daily. */
data class Recurrence(val days: Set<java.time.DayOfWeek>)

data class DateRange(val start: java.time.LocalDate, val endInclusive: java.time.LocalDate)

enum class Toggle { ON, OFF, TOGGLE }

enum class CameraMode { PHOTO, SELFIE, VIDEO }

enum class VolumeStream { MEDIA, RING, ALARM, CALL }

enum class VolumeAction { UP, DOWN, SET, MUTE, UNMUTE, MAX }

enum class LevelAction { UP, DOWN, SET, MAX, MIN }

enum class DeviceSetting { FLASHLIGHT, VOLUME, RINGER, DND, BRIGHTNESS }

enum class RingerMode { SILENT, VIBRATE, NORMAL }

enum class SettingPanel { WIFI, BLUETOOTH, MOBILE_DATA, AIRPLANE, DARK_MODE, BATTERY_SAVER, LOCATION, NFC, HOTSPOT }

enum class MediaAction { PLAY, PAUSE, RESUME, NEXT, PREVIOUS, STOP }

enum class TravelMode { DRIVING, WALKING, TRANSIT, CYCLING }
