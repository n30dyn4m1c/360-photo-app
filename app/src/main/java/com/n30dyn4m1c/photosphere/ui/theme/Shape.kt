package com.n30dyn4m1c.photosphere.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner radii, as one ladder rather than a number picked per call site.
 *
 * The app's chrome is soft-cornered throughout — a hard rectangle over a
 * photograph reads as a system dialog interrupting it, where a rounded pane
 * reads as something floating above it. The radii climb with the size of the
 * surface so that a chip and a sheet look like the same family seen at two
 * scales, which a single shared radius does not achieve.
 */
val PhotoSphereShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/** Fully rounded — pills, chips, and the primary actions. */
val PillShape = RoundedCornerShape(percent = 50)
