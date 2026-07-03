package tw.dystopia.mockgpsjoystick

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class Coordinate(
    val latitude: Double,
    val longitude: Double,
)

data class RuntimeSnapshot(
    val startCoordinate: Coordinate = Coordinate(25.0330, 121.5654),
    val currentCoordinate: Coordinate = startCoordinate,
    val speedKmh: Double = 5.0,
    val joystickAngle: Double = -90.0,
    val joystickStrength: Double = 0.0,
    val actualSpeedMps: Float = 0f,
    val bearing: Float = 0f,
    val starting: Boolean = false,
    val serviceRunning: Boolean = false,
    val overlayVisible: Boolean = false,
    val lastPushMillis: Long = 0L,
    val error: String? = null,
)

object RuntimeState {
    @Volatile
    private var current = RuntimeSnapshot()

    @Volatile
    var activityVisible: Boolean = false
        private set

    fun snapshot(): RuntimeSnapshot = current

    @Synchronized
    fun setActivityVisible(visible: Boolean) {
        activityVisible = visible
    }

    @Synchronized
    fun prepareStart(start: Coordinate, speedKmh: Double) {
        current = RuntimeSnapshot(
            startCoordinate = start,
            currentCoordinate = start,
            speedKmh = speedKmh,
            starting = true,
        )
    }

    @Synchronized
    fun markRunning(overlayVisible: Boolean) {
        current = current.copy(
            starting = false,
            serviceRunning = true,
            overlayVisible = overlayVisible,
            error = null,
        )
    }

    @Synchronized
    fun setJoystick(angle: Double, strength: Double) {
        current = current.copy(
            joystickAngle = angle,
            joystickStrength = strength.coerceIn(0.0, 1.0),
        )
    }

    @Synchronized
    fun setSpeed(speedKmh: Double) {
        if (speedKmh in listOf(1.0, 3.0, 5.0, 10.0)) {
            current = current.copy(speedKmh = speedKmh)
        }
    }

    @Synchronized
    fun jumpTo(coordinate: Coordinate): Boolean {
        if (!current.serviceRunning ||
            !coordinate.latitude.isFinite() || coordinate.latitude !in -90.0..90.0 ||
            !coordinate.longitude.isFinite() || coordinate.longitude !in -180.0..180.0
        ) {
            return false
        }
        current = current.copy(
            currentCoordinate = coordinate,
            joystickStrength = 0.0,
            actualSpeedMps = 0f,
        )
        return true
    }

    @Synchronized
    fun updateStartCoordinate(coordinate: Coordinate, moveCurrent: Boolean): Boolean {
        if (!current.serviceRunning ||
            !coordinate.latitude.isFinite() || coordinate.latitude !in -90.0..90.0 ||
            !coordinate.longitude.isFinite() || coordinate.longitude !in -180.0..180.0
        ) {
            return false
        }
        current = current.copy(
            startCoordinate = coordinate,
            currentCoordinate = if (moveCurrent) coordinate else current.currentCoordinate,
            joystickStrength = if (moveCurrent) 0.0 else current.joystickStrength,
            actualSpeedMps = if (moveCurrent) 0f else current.actualSpeedMps,
        )
        return true
    }

    @Synchronized
    fun resetToStart(): Boolean {
        if (!current.serviceRunning) return false
        current = current.copy(
            currentCoordinate = current.startCoordinate,
            joystickStrength = 0.0,
            actualSpeedMps = 0f,
        )
        return true
    }

    @Synchronized
    fun updateLocation(result: MovementResult, pushedAt: Long) {
        current = current.copy(
            currentCoordinate = result.coordinate,
            actualSpeedMps = result.actualSpeedMps,
            bearing = result.bearing,
            lastPushMillis = pushedAt,
        )
    }

    @Synchronized
    fun fail(message: String) {
        current = current.copy(
            starting = false,
            serviceRunning = false,
            overlayVisible = false,
            joystickStrength = 0.0,
            actualSpeedMps = 0f,
            error = message,
        )
    }

    @Synchronized
    fun recordErrorIfEmpty(message: String) {
        if (current.error == null) current = current.copy(error = message)
    }

    @Synchronized
    fun markStopped(preserveError: Boolean = true) {
        current = current.copy(
            starting = false,
            serviceRunning = false,
            overlayVisible = false,
            joystickStrength = 0.0,
            actualSpeedMps = 0f,
            error = if (preserveError) current.error else null,
        )
    }
}

data class MovementResult(
    val coordinate: Coordinate,
    val bearing: Float,
    val actualSpeedMps: Float,
)

object MovementMath {
    private const val EARTH_RADIUS_METERS = 6_371_000.0
    const val DEAD_ZONE = 0.08

    fun next(
        current: Coordinate,
        screenAngleDegrees: Double,
        strength: Double,
        speedKmh: Double,
        deltaSeconds: Double,
    ): MovementResult {
        val effectiveStrength = strength.coerceIn(0.0, 1.0).takeIf { it >= DEAD_ZONE } ?: 0.0
        val speedMps = speedKmh / 3.6 * effectiveStrength
        val bearing = normalizeDegrees(screenAngleDegrees + 90.0)
        val distance = speedMps * deltaSeconds.coerceAtLeast(0.0)

        if (distance == 0.0) {
            return MovementResult(current, bearing.toFloat(), 0f)
        }

        val angularDistance = distance / EARTH_RADIUS_METERS
        val latitude1 = Math.toRadians(current.latitude)
        val longitude1 = Math.toRadians(current.longitude)
        val bearingRadians = Math.toRadians(bearing)
        val latitude2 = asin(
            sin(latitude1) * cos(angularDistance) +
                cos(latitude1) * sin(angularDistance) * cos(bearingRadians),
        )
        val longitude2 = longitude1 + atan2(
            sin(bearingRadians) * sin(angularDistance) * cos(latitude1),
            cos(angularDistance) - sin(latitude1) * sin(latitude2),
        )

        return MovementResult(
            coordinate = Coordinate(
                latitude = Math.toDegrees(latitude2).coerceIn(-90.0, 90.0),
                longitude = normalizeLongitude(Math.toDegrees(longitude2)),
            ),
            bearing = bearing.toFloat(),
            actualSpeedMps = speedMps.toFloat(),
        )
    }

    fun debugSelfCheck() {
        val origin = Coordinate(0.0, 0.0)
        check(next(origin, -90.0, 1.0, 5.0, 1.0).coordinate.latitude > 0.0)
        check(next(origin, 0.0, 1.0, 5.0, 1.0).coordinate.longitude > 0.0)
        check(next(origin, 90.0, 1.0, 5.0, 1.0).coordinate.latitude < 0.0)
        check(next(origin, 180.0, 1.0, 5.0, 1.0).coordinate.longitude < 0.0)
        check(next(origin, 0.0, 0.01, 5.0, 1.0).coordinate == origin)
        check(next(Coordinate(0.0, 179.999999), 0.0, 1.0, 10.0, 2.0).coordinate.longitude in -180.0..180.0)
    }

    private fun normalizeDegrees(value: Double): Double = ((value % 360.0) + 360.0) % 360.0

    private fun normalizeLongitude(value: Double): Double = ((value + 540.0) % 360.0) - 180.0
}
