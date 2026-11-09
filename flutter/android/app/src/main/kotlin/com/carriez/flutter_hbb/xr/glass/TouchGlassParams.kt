package com.carriez.flutter_hbb.xr.glass

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

// The web experiment's parameters, field for field. Its "Copy Kotlin" output
// is a TouchGlassParams(...) expression that can replace PARAMS in
// MainActivity. Defaults are the values tuned there. Lengths are in dp,
// padding is a share of the screen width, angles are in degrees, time in s.

/** `style` is "organic" or "marble" (liquid marble, the Trackpad design's background). */
data class BackgroundParams(
    val style: String = "marble",
    val seed: Int = 684,
    // Organic
    val scale: Float = 1.69f,
    val warp: Float = 1.29f,
    val contrast: Float = 0.75f,
    val lightness: Float = 0.8f,
    // Liquid marble; frequencies are cycles per dp
    val warpScale: Float = 444f,
    val warpFrequency: Float = 0.0042f,
    val warpOctaves: Int = 3,
    val baseFrequency: Float = 0.0055f,
    val baseOctaves: Int = 5,
    val slope: Float = 2.6f,
    val offset: Float = -0.55f,
    // Liquid marble in motion: two oils that keep trying to mix and can't.
    val motion: Boolean = true,
    val flowSpeed: Float = 4f,
    val counterflow: Float = 0.5f,
    val swirlSize: Float = 240f,
    val change: Float = 0.04f,
    val friction: Float = 1f,
    val tension: Float = 0.8f,
    val attraction: Float = 6f,
    val attractionRange: Float = 80f,
    val crowding: Float = 1.8f,
    val interfaceWidth: Float = 16f,
    val detail: Float = 0.9f,
    // Device motion: the accelerometer's body force pushes the two oils by
    // their density difference (white − black, −1 to 1).
    val density: Float = -0.5f,
    val motionResponse: Float = 160f,
    val levelTime: Float = 6f,
    // A shake churns them: dp/s per m/s² of shake, eddy size, settle time.
    val shakeChurn: Float = 8f,
    val churnSize: Float = 90f,
    val churnTime: Float = 2f,
    // Both
    val saturation: Float = 0.02f,
    val hue: Float = 29f,
    val hueRange: Float = 0.35f,
    // Grain: additive for organic, overlay for marble
    val grain: Float = 0.065f,
    val grainOpacity: Float = 0.08f,
    val grainFrequency: Float = 0.9f,
) {
    /** Roughly how light the background is, 0 to 1, for choosing status bar icons. */
    val lightnessEstimate: Float
        get() = if (style == "marble") (slope * 0.5f + offset).coerceIn(0f, 1f) else lightness
}

data class SquircleParams(
    val padding: Float = 0.04f,
    val cornerRadius: Float = 35.5f,
    val smoothing: Float = 1f,
    val exponentTrim: Float = -0.94f,
)

data class LiquidParams(
    val thickness: Float = 35.5f,
    val contactAngle: Float = 85f,
    val ior: Float = 1.76f,
    val dispersion: Float = 0.245f,
    val imageDepth: Float = 14.5f,
    val frost: Float = 15.5f,
    val frostFade: Float = 26f,
    val milkiness: Float = 0.18f,
    val vibrance: Float = 1.7f,
    val reflections: Float = 1.04f,
    val rimLine: Float = 0.17f,
    val lightAngle: Float = 290f,
    val lightHeight: Float = 90f,
    val tiltLight: Boolean = true,
    val shadow: Float = 0.31f,
)

data class DotParams(
    val spacing: Float = 10.5f,
    val size: Float = 0.41f,
    val luminosity: Float = 1f,
    val variance: Float = 0.44f,
    val varianceGrain: Float = 0.7f,
    val opacity: Float = 0.73f,
    val depth: Float = 0.51f,
    val inset: Float = 8.5f,
    val hex: Boolean = false,
    val color: Int = 0xFFFFFFFF.toInt(),
    val drift: Float = 0.6f,
    val returnTime: Float = 1.2f,
)

data class FluidParams(
    val waveSpeed: Float = 160f,
    val surfaceTension: Float = 2f,
    val viscosity: Float = 300f,
    val settling: Float = 2.1f,
    val edgeAbsorption: Float = 5f,
    val caustics: Float = 0.15f,
    val timeScale: Float = 1f,
)

data class TouchParams(
    val radius: Float = 36f,
    val contactScale: Float = 1.8f,
    val push: Float = 1.2f,
    val meniscus: Float = 0.25f,
    val tapImpulse: Float = 0.5f,
    val speedPush: Float = 0.6f,
    // The edge magnet, which the web playground runs; this app doesn't yet.
    val magnetOut: Float = 0.8f,
    val magnetIn: Float = 0.27f,
    val magnetRange: Float = 60f,
    val magnetReach: Float = 117f,
    val magnetWidth: Float = 80f,
    val magnetWake: Float = 1f,
)

// The G-force (GForce.kt): gains on the phone's motion, 1 as real oil's tilt.
data class GForceParams(
    val enabled: Boolean = true,
    val push: Float = 1f,
    val spin: Float = 1f,
    val load: Float = 1f,
    val edge: Float = 0f,
    val levelTime: Float = 4f,
)

data class TouchGlassParams(
    val background: BackgroundParams = BackgroundParams(),
    val squircle: SquircleParams = SquircleParams(),
    val liquid: LiquidParams = LiquidParams(),
    val dots: DotParams = DotParams(),
    val fluid: FluidParams = FluidParams(),
    val touch: TouchParams = TouchParams(),
    val gforce: GForceParams = GForceParams(),
)

/**
 * Corner geometry from the squircle controls, as in the web experiment's
 * squircleCorner(): the curve leaves the straight edge at
 * (1 + smoothing) × radius, with the exponent that keeps its crossing of the
 * diagonal where a circular arc of `radius` would cross it.
 */
class SquircleCorner(squircle: SquircleParams) {
    val extentDp = squircle.cornerRadius * (1f + squircle.smoothing)
    val exponent = max(
        1.2f,
        (-ln(2.0) / ln(1.0 - (1.0 - sqrt(0.5)) / (1.0 + squircle.smoothing))).toFloat() + squircle.exponentTrim,
    )
}
