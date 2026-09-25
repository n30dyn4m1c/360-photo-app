package com.n30dyn4m1c.photosphere.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * The app's one palette.
 *
 * Everything the user looks at sits on top of either a live viewfinder or a
 * finished photograph, so the design is dark-first and almost colourless by
 * default — the picture supplies the colour, the chrome stays out of its way.
 *
 * What colour budget there is buys exactly two signals, and they carry the
 * whole visual language of guided capture:
 *
 *  - [SphereAccent] — done, locked, aligned, captured. The "yes".
 *  - [SphereActive] — the live target: aim here next.
 *
 * They are warm/cool opposites so the two never trade places at a glance, and
 * both are bright enough to hold up against a blown-out sky or a dark room,
 * which is the real constraint on chrome drawn over an arbitrary camera image.
 *
 * These live here, once, rather than as hex literals re-typed wherever a piece
 * of chrome happens to need one — that duplication is how a design drifts.
 */

// --- Signal colours -------------------------------------------------------

/** Aligned, locked, captured, complete — the single "yes" of the interface. */
val SphereAccent = Color(0xFF3DDC84)

/** The live target the reticle is being sent to. Warm, so it never reads as done. */
val SphereActive = Color(0xFFFFC24B)

// --- Camera chrome --------------------------------------------------------
//
// The HUD floats over a moving image, so its surfaces are one consistent
// smoked glass rather than a handful of similar-but-different blacks. Two
// weights: the standard pane, and a lighter one for controls that should
// recede until they matter.

/** The material every HUD pane is cut from. */
val GlassSurface = Color(0xFF000000).copy(alpha = 0.55f)

/** The same glass, lighter — for chrome that should recede. */
val GlassSurfaceDim = Color(0xFF000000).copy(alpha = 0.38f)

/** Foreground on glass: not pure white, which glares against a night scene. */
val GlassContent = Color(0xFFF2F5F7)

/** Secondary text and inert controls on glass. */
val GlassContentDim = Color(0xFFF2F5F7).copy(alpha = 0.62f)

/** Top and bottom gradient wash that keeps chrome legible over a bright scene. */
val ChromeScrim = Color(0xFF000000).copy(alpha = 0.60f)

// --- Surfaces -------------------------------------------------------------
//
// A near-black ladder rather than Material's default greys: the result screen
// frames a photograph, and a neutral-cool ground is what stops the chrome from
// tinting the picture it surrounds.

/** The ground everything sits on. */
val SphereBackground = Color(0xFF0A0D10)

/** Cards and sheets lifted off the background. */
val SphereSurface = Color(0xFF12171B)

/** The next step up: nested surfaces, pressed states, the diagnostics block. */
val SphereSurfaceHigh = Color(0xFF1B2228)

/** Primary text. Warm-neutral off-white; pure white is harsh at this contrast. */
val SphereOnSurface = Color(0xFFE7EDF1)

/** Captions, metadata, supporting copy. */
val SphereOnSurfaceVariant = Color(0xFF9DA9B3)

/** Hairlines, button outlines, dividers. */
val SphereOutline = Color(0xFF333D45)

/** The well a photograph is set into, darker than the surface around it. */
val PhotoWell = Color(0xFF05070A)
