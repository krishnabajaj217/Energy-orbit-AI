package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

// ==========================================
// MODELS & CONFIG
// ==========================================

enum class PowerType(
    val title: String,
    val coreColor: Color,
    val accentColor: Color,
    val element: String,
    val description: String
) {
    ENERGY_BLAST(
        "Energy Blast", 
        Color(0xFF00C8FF), 
        Color(0xFF0044FF), 
        "PLASMA", 
        "High-voltage kinetic blast"
    ),
    FIRE_ORB(
        "Fire Orb", 
        Color(0xFFFF5D00), 
        Color(0xFFFFB400), 
        "IGNIS", 
        "Searing thermal destruction"
    ),
    PLASMA_ORB(
        "Plasma Orb", 
        Color(0xFFAF52DE), 
        Color(0xFFFF2D55), 
        "VOLT", 
        "Electromagnetic crackle sphere"
    ),
    COSMIC_ORB(
        "Cosmic Orb", 
        Color(0xFFFFFFFF), 
        Color(0xFF64D2FF), 
        "GRAVITY", 
        "Gravitational matter condenser"
    )
}

enum class GestureState {
    REFORMING,
    CHARGING,
    STABLE,
    THROWING,
    EXPLODED
}

data class VisualParticle(
    val id: Int,
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var alpha: Float,
    var size: Float,
    var age: Int,
    var maxAge: Int,
    var angle: Float = 0f,
    var speed: Float = 0f,
    var distance: Float = 0f
)

data class Shockwave(
    var x: Float,
    var y: Float,
    var radius: Float,
    var maxRadius: Float,
    var alpha: Float,
    val color: Color
)

// Main Activity
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(darkTheme = true, dynamicColor = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF050816)
                ) {
                    EnergyOrbSimulatorApp()
                }
            }
        }
    }
}

// ==========================================
// MAIN SIMULATOR SCREEN
// ==========================================

@Composable
fun EnergyOrbSimulatorApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Permissions State
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    // Camera Settings
    var isFrontCamera by remember { mutableStateOf(true) }
    var useCameraMotion by remember { mutableStateOf(true) }
    var isAutoPilotDemo by remember { mutableStateOf(true) }

    // Active State
    var activePower by remember { mutableStateOf(PowerType.ENERGY_BLAST) }
    var gestureState by remember { mutableStateOf(GestureState.CHARGING) }
    var powerLevel by remember { mutableStateOf(0f) }

    // Hand coordinates (Normalized 0f..1f within Canvas size)
    var leftHandX by remember { mutableStateOf(0.3f) }
    var leftHandY by remember { mutableStateOf(0.6f) }
    var rightHandX by remember { mutableStateOf(0.7f) }
    var rightHandY by remember { mutableStateOf(0.6f) }

    // Motion centoid detected from CameraX Analyzer
    var motionCentroidX by remember { mutableStateOf(0.5f) }
    var motionCentroidY by remember { mutableStateOf(0.5f) }
    var lastMotionCentroidUpdate by remember { mutableStateOf(0L) }

    // Manual adjustment parameters (for when simulator mode is used)
    var manualDistanceFactor by remember { mutableStateOf(0.5f) } // 0.2f (close) to 1.0f (far)

    // Derived stats
    val palmDistancePixels = remember(leftHandX, leftHandY, rightHandX, rightHandY) {
        val dx = (rightHandX - leftHandX)
        val dy = (rightHandY - leftHandY)
        sqrt(dx * dx + dy * dy)
    }

    val currentOrbSizeCategory = when {
        palmDistancePixels < 0.25f -> "SMALL"
        palmDistancePixels < 0.45f -> "MEDIUM"
        else -> "LARGE"
    }

    // Clock
    var currentTimeString by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val sdf = SimpleDateFormat("HH:mm:ss 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        while (true) {
            currentTimeString = sdf.format(Date())
            delay(1000)
        }
    }

    // Particle / Physics state holder
    val particlesList = remember { mutableStateListOf<VisualParticle>() }
    val sparkList = remember { mutableStateListOf<VisualParticle>() }
    val shockwaveList = remember { mutableStateListOf<Shockwave>() }
    val explosionSparks = remember { mutableStateListOf<VisualParticle>() }

    // Animation Tick Counter
    var frameTick by remember { mutableStateOf(0) }

    // Active Orb Center (dynamic transition or flying)
    var orbX by remember { mutableStateOf(0.5f) }
    var orbY by remember { mutableStateOf(0.6f) }
    var orbVelocityY by remember { mutableStateOf(0f) }

    // Animation loop & Physics Controller
    LaunchedEffect(gestureState, activePower, isAutoPilotDemo, manualDistanceFactor, useCameraMotion) {
        var particleIdCounter = 0
        while (true) {
            frameTick = (frameTick + 1) % 100000

            // 1. Auto Pilot Sequence / Manual Simulation
            if (isAutoPilotDemo) {
                // Animate hands organically in superhero patterns
                val slowTime = frameTick * 0.02f
                val distanceOscillation = 0.35f + 0.25f * sin(slowTime * 0.5f) // cycle from close to far
                
                // Drift center based on motion
                val driftX = if (useCameraMotion) (motionCentroidX - 0.5f) * 0.2f else 0f
                val driftY = if (useCameraMotion) (motionCentroidY - 0.5f) * 0.2f else 0f

                val centerX = 0.5f + driftX + 0.05f * sin(slowTime * 0.3f)
                val centerY = 0.6f + driftY + 0.03f * cos(slowTime * 0.4f)

                leftHandX = centerX - distanceOscillation * 0.4f
                leftHandY = centerY + 0.05f * sin(slowTime * 0.8f)

                rightHandX = centerX + distanceOscillation * 0.4f
                rightHandY = centerY + 0.05f * cos(slowTime * 0.8f)

                // Trigger Throw gesture automatically in the loop when at peak distance
                val currentCyclePosition = (slowTime * 0.5f) % (2 * PI.toFloat())
                if (gestureState == GestureState.STABLE && currentCyclePosition > 4.5f && currentCyclePosition < 4.7f) {
                    // Trigger dynamic release
                    gestureState = GestureState.THROWING
                    orbVelocityY = -0.04f
                }
            } else {
                // Manual Slider Control
                val driftX = if (useCameraMotion) (motionCentroidX - 0.5f) * 0.2f else 0f
                val driftY = if (useCameraMotion) (motionCentroidY - 0.5f) * 0.2f else 0f

                val centerX = 0.5f + driftX
                val centerY = 0.6f + driftY

                leftHandX = centerX - manualDistanceFactor * 0.3f
                leftHandY = centerY
                rightHandX = centerX + manualDistanceFactor * 0.3f
                rightHandY = centerY
            }

            // 2. Orb Center Calculation & States
            val midX = (leftHandX + rightHandX) / 2f
            val midY = (leftHandY + rightHandY) / 2f

            when (gestureState) {
                GestureState.REFORMING -> {
                    // Gradual fade back
                    orbX = midX
                    orbY = midY
                    powerLevel += 2f
                    if (powerLevel >= 10f) {
                        gestureState = GestureState.CHARGING
                    }
                }
                GestureState.CHARGING -> {
                    orbX = midX
                    orbY = midY
                    powerLevel += 1.2f
                    if (powerLevel >= 100f) {
                        powerLevel = 100f
                        gestureState = GestureState.STABLE
                        // Emit initial shockwave
                        shockwaveList.add(
                            Shockwave(
                                x = midX,
                                y = midY,
                                radius = 0.05f,
                                maxRadius = 0.25f,
                                alpha = 1.0f,
                                color = activePower.coreColor
                            )
                        )
                    }

                    // Create gravity pull particles (sucking energy into core)
                    if (frameTick % 2 == 0) {
                        val angle = (Math.random() * 2 * PI).toFloat()
                        val dist = 0.2f + (Math.random() * 0.15f).toFloat()
                        particlesList.add(
                            VisualParticle(
                                id = particleIdCounter++,
                                x = orbX + cos(angle) * dist,
                                y = orbY + sin(angle) * dist,
                                vx = -cos(angle) * 0.015f,
                                vy = -sin(angle) * 0.015f,
                                alpha = 0.8f,
                                size = 4f + (Math.random() * 8f).toFloat(),
                                age = 0,
                                maxAge = 20,
                                angle = angle,
                                speed = -0.015f,
                                distance = dist
                            )
                        )
                    }
                }
                GestureState.STABLE -> {
                    orbX = midX
                    orbY = midY
                    powerLevel = 100f

                    // Continuously emit energy sparks and orbital trails
                    if (particlesList.size < 40) {
                        val angle = (Math.random() * 2 * PI).toFloat()
                        particlesList.add(
                            VisualParticle(
                                id = particleIdCounter++,
                                x = orbX,
                                y = orbY,
                                vx = 0f,
                                vy = 0f,
                                alpha = 1.0f,
                                size = 6f + (Math.random() * 10f).toFloat(),
                                age = 0,
                                maxAge = 40 + (Math.random() * 30).toInt(),
                                angle = angle,
                                speed = 0.05f + (Math.random() * 0.05f).toFloat(),
                                distance = 0.03f + (Math.random() * 0.05f).toFloat() * palmDistancePixels * 3f
                            )
                        )
                    }

                    // Floating ambient heat sparks
                    if (frameTick % 3 == 0) {
                        sparkList.add(
                            VisualParticle(
                                id = particleIdCounter++,
                                x = orbX + ((Math.random() - 0.5) * 0.05).toFloat(),
                                y = orbY + ((Math.random() - 0.5) * 0.05).toFloat(),
                                vx = ((Math.random() - 0.5) * 0.005).toFloat(),
                                vy = -0.008f - (Math.random() * 0.008f).toFloat(),
                                alpha = 1f,
                                size = 3f + (Math.random() * 6f).toFloat(),
                                age = 0,
                                maxAge = 25
                            )
                        )
                    }
                }
                GestureState.THROWING -> {
                    // Fly upwards
                    orbY += orbVelocityY
                    // Slowly add speed/acceleration
                    orbVelocityY -= 0.003f

                    // Drag trail
                    if (frameTick % 2 == 0) {
                        particlesList.add(
                            VisualParticle(
                                id = particleIdCounter++,
                                x = orbX,
                                y = orbY,
                                vx = ((Math.random() - 0.5) * 0.02).toFloat(),
                                vy = -orbVelocityY * 0.3f,
                                alpha = 0.8f,
                                size = 12f + (Math.random() * 10f).toFloat(),
                                age = 0,
                                maxAge = 15
                            )
                        )
                    }

                    // Impact detection
                    if (orbY < 0.15f) {
                        gestureState = GestureState.EXPLODED
                        powerLevel = 0f
                        // Trigger 60+ sparks for high-fidelity blast
                        for (i in 0..70) {
                            val burstAngle = (Math.random() * 2 * PI).toFloat()
                            val speedFactor = 0.01f + (Math.random() * 0.04f).toFloat()
                            explosionSparks.add(
                                VisualParticle(
                                    id = particleIdCounter++,
                                    x = orbX,
                                    y = orbY,
                                    vx = cos(burstAngle) * speedFactor,
                                    vy = sin(burstAngle) * speedFactor - 0.005f, // slight upward drift
                                    alpha = 1.0f,
                                    size = 5f + (Math.random() * 12f).toFloat(),
                                    age = 0,
                                    maxAge = 40 + (Math.random() * 30).toInt()
                                )
                            )
                        }
                        // Trigger massive expanding ring
                        shockwaveList.add(
                            Shockwave(
                                x = orbX,
                                y = orbY,
                                radius = 0.02f,
                                maxRadius = 0.5f,
                                alpha = 1.0f,
                                color = activePower.coreColor
                            )
                        )
                    }
                }
                GestureState.EXPLODED -> {
                    // Wait for explosion particles to die, then reform
                    if (explosionSparks.isEmpty() && shockwaveList.isEmpty()) {
                        gestureState = GestureState.REFORMING
                    }
                }
            }

            // 3. Update Existing Particles
            // Particles list (rotation, gravity, trails)
            val iterator = particlesList.iterator()
            while (iterator.hasNext()) {
                val p = iterator.next()
                p.age++
                if (p.age >= p.maxAge) {
                    iterator.remove()
                } else {
                    if (gestureState == GestureState.CHARGING) {
                        // Gravitational convergence
                        p.x += p.vx
                        p.y += p.vy
                        p.alpha = 1f - (p.age.toFloat() / p.maxAge)
                    } else if (gestureState == GestureState.STABLE) {
                        // Rotation orbit around core
                        p.angle += p.speed
                        p.alpha = 1f - (p.age.toFloat() / p.maxAge)
                        p.x = orbX + cos(p.angle) * p.distance
                        p.y = orbY + sin(p.angle) * p.distance
                    } else {
                        // Linear drift
                        p.x += p.vx
                        p.y += p.vy
                        p.alpha = 1f - (p.age.toFloat() / p.maxAge)
                    }
                }
            }

            // Ambient sparks list (drifts upward & fades)
            val sparkIterator = sparkList.iterator()
            while (sparkIterator.hasNext()) {
                val p = sparkIterator.next()
                p.age++
                if (p.age >= p.maxAge) {
                    sparkIterator.remove()
                } else {
                    p.x += p.vx
                    p.y += p.vy
                    p.alpha = 1f - (p.age.toFloat() / p.maxAge)
                }
            }

            // Shockwaves expansion
            val shockIterator = shockwaveList.iterator()
            while (shockIterator.hasNext()) {
                val s = shockIterator.next()
                s.radius += 0.015f
                s.alpha = 1f - (s.radius / s.maxRadius)
                if (s.radius >= s.maxRadius) {
                    shockIterator.remove()
                }
            }

            // Explosion sparks update (physics/gravity)
            val explosionIterator = explosionSparks.iterator()
            while (explosionIterator.hasNext()) {
                val p = explosionIterator.next()
                p.age++
                if (p.age >= p.maxAge) {
                    explosionIterator.remove()
                } else {
                    p.x += p.vx
                    p.y += p.vy
                    p.vy += 0.0012f // Gravity pull down!
                    p.vx *= 0.96f   // drag
                    p.alpha = 1f - (p.age.toFloat() / p.maxAge)
                }
            }

            delay(16) // Target 60 FPS update tick
        }
    }

    // Main Column Container
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050816))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {

        // ==========================================
        // 1. TOP HUD BAR
        // ==========================================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Pulsing cyan status dot
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulseAlpha"
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .graphicsLayer { alpha = pulseAlpha }
                        .background(Color(0xFF22D3EE), CircleShape)
                        .shadow(4.dp, CircleShape, spotColor = Color(0xFF22D3EE), ambientColor = Color(0xFF22D3EE))
                )
                Text(
                    text = "LIVE FEED // SYSTEM ACTIVE",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF22D3EE).copy(alpha = 0.8f),
                    letterSpacing = 1.sp
                )
            }

            // Glass badge
            Box(
                modifier = Modifier
                    .background(Color(0x0EFFFFFF), RoundedCornerShape(100.dp))
                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(100.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "FPS: 60",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }

        // ==========================================
        // 2. MAIN AR VIEWPORT (Webcam Feed frame)
        // ==========================================
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(24.dp))
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF0A0F1F), Color(0xFF050816))
                    )
                )
        ) {
            // Camera video preview
            if (hasCameraPermission) {
                CameraPreview(
                    isFrontCamera = isFrontCamera,
                    onMotionDetected = { x, y ->
                        if (useCameraMotion) {
                            motionCentroidX = x
                            motionCentroidY = y
                            lastMotionCentroidUpdate = System.currentTimeMillis()
                        }
                    }
                )
            } else {
                // Interactive grid background representing camera simulator
                VirtualCameraBackground(frameTick = frameTick)
            }

            // Particle overlay and glowing canvas
            SimulationCanvas(
                frameTick = frameTick,
                leftX = leftHandX,
                leftY = leftHandY,
                rightX = rightHandX,
                rightY = rightHandY,
                palmDistance = palmDistancePixels,
                orbX = orbX,
                orbY = orbY,
                activePower = activePower,
                gestureState = gestureState,
                powerLevel = powerLevel,
                particles = particlesList,
                sparks = sparkList,
                shockwaves = shockwaveList,
                explosions = explosionSparks,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("interactive_simulation_canvas")
                    .pointerInput(isAutoPilotDemo) {
                        if (!isAutoPilotDemo) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val size = this.size
                                    val fx = offset.x / size.width
                                    val fy = offset.y / size.height
                                    val dl = (fx - leftHandX) * (fx - leftHandX) + (fy - leftHandY) * (fy - leftHandY)
                                    val dr = (fx - rightHandX) * (fx - rightHandX) + (fy - rightHandY) * (fy - rightHandY)
                                    if (dl < dr) {
                                        leftHandX = fx.coerceIn(0.1f, 0.9f)
                                        leftHandY = fy.coerceIn(0.2f, 0.9f)
                                    } else {
                                        rightHandX = fx.coerceIn(0.1f, 0.9f)
                                        rightHandY = fy.coerceIn(0.2f, 0.9f)
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val size = this.size
                                    val dx = dragAmount.x / size.width
                                    val dy = dragAmount.y / size.height
                                    val lastOffset = change.position
                                    val fx = lastOffset.x / size.width
                                    val fy = lastOffset.y / size.height
                                    val dl = (fx - leftHandX) * (fx - leftHandX) + (fy - leftHandY) * (fy - leftHandY)
                                    val dr = (fx - rightHandX) * (fx - rightHandX) + (fy - rightHandY) * (fy - rightHandY)
                                    if (dl < dr) {
                                        leftHandX = (leftHandX + dx).coerceIn(0.05f, 0.95f)
                                        leftHandY = (leftHandY + dy).coerceIn(0.2f, 0.9f)
                                    } else {
                                        rightHandX = (rightHandX + dx).coerceIn(0.05f, 0.95f)
                                        rightHandY = (rightHandY + dy).coerceIn(0.2f, 0.9f)
                                    }
                                }
                            )
                        }
                    }
            )

            // Bottom Left Viewport HUD Labels
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "PALM DISTANCE",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White.copy(alpha = 0.4f),
                    letterSpacing = 1.sp
                )
                Text(
                    text = String.format(Locale.US, "%.0f mm", palmDistancePixels * 1000f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Light,
                    fontFamily = FontFamily.SansSerif,
                    color = Color(0xFF22D3EE),
                    letterSpacing = 1.5.sp
                )
            }

            // Bottom Right Viewport HUD Labels
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "ENERGY INTEGRITY",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White.copy(alpha = 0.4f),
                    letterSpacing = 1.sp
                )
                Text(
                    text = "${powerLevel.toInt()}%",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Light,
                    fontFamily = FontFamily.SansSerif,
                    color = activePower.coreColor,
                    letterSpacing = 1.5.sp
                )
            }
        }

        // ==========================================
        // 3. INTERACTION CONTROLS & DOCK
        // ==========================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Power Meter Progress Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PLASMA INTEGRITY",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.6f),
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = if (gestureState == GestureState.EXPLODED) "DISCHARGED" else if (gestureState == GestureState.THROWING) "LAUNCHED" else "STABLE",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (gestureState == GestureState.EXPLODED) Color(0xFFFF2D55) else Color.White
                    )
                }
                
                // Futuristic progress bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(100.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(powerLevel / 100f)
                            .fillMaxHeight()
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(Color(0xFF3B82F6), Color(0xFF8B5CF6))
                                ),
                                shape = RoundedCornerShape(100.dp)
                            )
                            .shadow(
                                elevation = 8.dp,
                                shape = RoundedCornerShape(100.dp),
                                spotColor = Color(0xFF8B5CF6),
                                ambientColor = Color(0xFF8B5CF6)
                            )
                    )
                }
            }

            // Power Selectors Row Grid
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PowerType.values().forEach { power ->
                    val isSelected = activePower == power
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSelected) Color.White.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.03f))
                            .border(
                                width = 1.dp,
                                color = if (isSelected) power.coreColor.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable {
                                activePower = power
                                shockwaveList.add(
                                    Shockwave(
                                        x = orbX,
                                        y = orbY,
                                        radius = 0.05f,
                                        maxRadius = 0.25f,
                                        alpha = 1.0f,
                                        color = power.coreColor
                                    )
                                )
                            }
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Glowing core orb circle
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .background(power.coreColor, CircleShape)
                                .shadow(
                                    elevation = 6.dp,
                                    shape = CircleShape,
                                    spotColor = power.coreColor,
                                    ambientColor = power.coreColor
                                )
                        )
                        Text(
                            text = power.title.split(" ").first().uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.5f),
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            // Interactive manual distance slider (visible in Sandbox Mode)
            if (!isAutoPilotDemo) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SANDBOX CONTROL (PALM DISTANCE SIMULATION)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00C8FF),
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "${(manualDistanceFactor * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = manualDistanceFactor,
                        onValueChange = { manualDistanceFactor = it },
                        valueRange = 0.15f..0.95f,
                        colors = SliderDefaults.colors(
                            thumbColor = activePower.coreColor,
                            activeTrackColor = activePower.coreColor,
                            inactiveTrackColor = Color(0xFF1E293B)
                        )
                    )
                }
            }

            // Main Gesture Action Trigger & Controls Dock
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Front / Back camera toggle button
                IconButton(
                    onClick = { isFrontCamera = !isFrontCamera },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Flip Camera",
                        tint = Color.White
                    )
                }

                // Auto pilot play/pause toggle button
                IconButton(
                    onClick = { isAutoPilotDemo = !isAutoPilotDemo },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isAutoPilotDemo) Color(0x2200C8FF) else Color.White.copy(alpha = 0.05f))
                        .border(
                            width = 1.dp,
                            color = if (isAutoPilotDemo) Color(0x6600C8FF) else Color.White.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(16.dp)
                        )
                ) {
                    Icon(
                        imageVector = if (isAutoPilotDemo) Icons.Default.PlayArrow else Icons.Default.Menu,
                        contentDescription = "Toggle Auto Pilot",
                        tint = if (isAutoPilotDemo) Color(0xFF00C8FF) else Color.White
                    )
                }

                // Interactive Main Action Release Energy Button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color(0x1AFFFFFF), Color.Transparent)
                            )
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                        .clickable(enabled = gestureState == GestureState.STABLE) {
                            gestureState = GestureState.THROWING
                            orbVelocityY = -0.045f
                        }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (gestureState == GestureState.STABLE) "LAUNCH POWER BLAST" else "WAITING FOR FOCUS",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = if (gestureState == GestureState.STABLE) Color.White else Color.White.copy(alpha = 0.4f),
                            letterSpacing = 1.5.sp
                        )
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Launch",
                            tint = if (gestureState == GestureState.STABLE) Color.White else Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Ticker / Status message console banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .background(Color(0x0F00C8FF), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0x1F00C8FF), RoundedCornerShape(12.dp))
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                val instruction = when (gestureState) {
                    GestureState.REFORMING -> "ORB EVAPORATED. Condensing atomic plasma grid... Please stand by."
                    GestureState.CHARGING -> "INCOMING SYNC: Move palms together to gather elemental force (${powerLevel.toInt()}%)."
                    GestureState.STABLE -> "ORB STABLE: Tap LAUNCH POWER BLAST or Swipe rapid forward gesture to fire element!"
                    GestureState.THROWING -> "ELEMENT LAUNCHED: High acceleration kinetic trajectory detected!"
                    GestureState.EXPLODED -> "KINETIC IMPACT DETECTED! Spectacular elemental energy collapse unleashed."
                }
                Text(
                    text = instruction,
                    fontSize = 10.sp,
                    color = Color(0xFF64D2FF),
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ==========================================
// 4. CAMERAX CAMERA PREVIEW & ANALYZER
// ==========================================

@Composable
fun CameraPreview(
    isFrontCamera: Boolean,
    onMotionDetected: (Float, Float) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                // Simple high-efficiency real-time motion/luminance detector.
                // Does NOT require any external network connection or heavyweight models.
                // Works 100% offline and in virtual devices!
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(160, 120))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                var prevBuffer: ByteArray? = null

                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
                    val buffer = imageProxy.planes[0].buffer
                    val data = buffer.toByteArray()
                    
                    if (prevBuffer != null && prevBuffer!!.size == data.size) {
                        // Compare current frame with previous frame to locate motion centroid
                        var totalDiff = 0L
                        var sumX = 0L
                        var sumY = 0L
                        val width = imageProxy.width
                        val height = imageProxy.height

                        // Sub-sample grid for 60FPS high performance
                        val step = 4
                        for (y in 0 until height step step) {
                            for (x in 0 until width step step) {
                                val idx = y * width + x
                                val diff = abs((data[idx].toInt() and 0xFF) - (prevBuffer!![idx].toInt() and 0xFF))
                                if (diff > 35) { // Motion threshold
                                    totalDiff += diff
                                    sumX += x * diff
                                    sumY += y * diff
                                }
                            }
                        }

                        if (totalDiff > 1500) {
                            // Centroid of motion
                            val mX = sumX.toFloat() / totalDiff / width
                            val mY = sumY.toFloat() / totalDiff / height
                            // Notify overlay (Invert X if front camera to act like mirror)
                            val finalX = if (isFrontCamera) 1f - mX else mX
                            onMotionDetected(finalX, mY)
                        }
                    }

                    prevBuffer = data
                    imageProxy.close()
                }

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(if (isFrontCamera) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK)
                    .build()

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    Log.e("CameraPreview", "Camera binding failed", e)
                }
            }, ContextCompat.getMainExecutor(context))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

// Helper extension
private fun ByteBuffer.toByteArray(): ByteArray {
    rewind()
    val data = ByteArray(remaining())
    get(data)
    return data
}

// ==========================================
// 5. CINEMATIC FALLBACK WORKSPACE GRID
// ==========================================

@Composable
fun VirtualCameraBackground(frameTick: Int) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Deep blue background colors
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF0F172A), Color(0xFF020617)),
                center = Offset(w / 2f, h * 0.6f),
                radius = (w * 0.8f).coerceAtLeast(1f)
            )
        )

        // Draw animated scanning grid lines representing digital lens fallback
        val gridSpacing = 60.dp.toPx()
        val offsetAnim = (frameTick * 0.8f) % gridSpacing

        // Vertical lines
        var currX = offsetAnim
        while (currX < w) {
            drawLine(
                color = Color(0x0C00C8FF),
                start = Offset(currX, 0f),
                end = Offset(currX, h),
                strokeWidth = 1f
            )
            currX += gridSpacing
        }

        // Horizontal lines
        var currY = (frameTick * 0.5f) % gridSpacing
        while (currY < h) {
            drawLine(
                color = Color(0x0C00C8FF),
                start = Offset(0f, currY),
                end = Offset(w, currY),
                strokeWidth = 1f
            )
            currY += gridSpacing
        }

        // Scanning line sweeping up & down
        val sweepY = (sin(frameTick * 0.015f) * 0.5f + 0.5f) * h
        drawLine(
            color = Color(0x1200C8FF),
            start = Offset(0f, sweepY),
            end = Offset(w, sweepY),
            strokeWidth = 4f
        )
        // Secondary sweep glow
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color(0x0500C8FF), Color.Transparent),
                startY = sweepY - 50,
                endY = sweepY + 50
            ),
            topLeft = Offset(0f, sweepY - 50),
            size = androidx.compose.ui.geometry.Size(w, 100f)
        )

        // Floating digital telemetry text on the canvas
        // (Simulates digital target tracking boxes)
        drawCircle(
            color = Color(0x1F30D5C8),
            center = Offset(w * 0.5f, h * 0.55f),
            radius = 120.dp.toPx() + 15 * sin(frameTick * 0.05f)
        )
    }
}

// ==========================================
// 6. MAGICAL PHYSICS RENDERER (Compose Canvas)
// ==========================================

@Composable
fun SimulationCanvas(
    frameTick: Int,
    leftX: Float,
    leftY: Float,
    rightX: Float,
    rightY: Float,
    palmDistance: Float,
    orbX: Float,
    orbY: Float,
    activePower: PowerType,
    gestureState: GestureState,
    powerLevel: Float,
    particles: List<VisualParticle>,
    sparks: List<VisualParticle>,
    shockwaves: List<Shockwave>,
    explosions: List<VisualParticle>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Calculate absolute positions of hand palms
        val leftPalmAbs = Offset(leftX * w, leftY * h)
        val rightPalmAbs = Offset(rightX * w, rightY * h)
        val orbAbs = Offset(orbX * w, orbY * h)

        // 1. Draw glowing background lens flares around the active orb
        if (gestureState != GestureState.EXPLODED && gestureState != GestureState.REFORMING) {
            val baseRadius = (35.dp.toPx() + 30.dp.toPx() * palmDistance * 3f) * (powerLevel / 100f)
            val pulseRadius = baseRadius * (1f + 0.12f * sin(frameTick * 0.08f))
            
            val flareRadius = (pulseRadius * 2.5f).coerceAtLeast(1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        activePower.coreColor.copy(alpha = 0.55f),
                        activePower.accentColor.copy(alpha = 0.25f),
                        Color.Transparent
                    ),
                    center = orbAbs,
                    radius = flareRadius
                ),
                center = orbAbs,
                radius = flareRadius
            )

            // Plasma lightning forks between hands and central orb (Plasma Orb Power)
            if (activePower == PowerType.PLASMA_ORB && (gestureState == GestureState.CHARGING || gestureState == GestureState.STABLE)) {
                drawPlasmaLightning(leftPalmAbs, orbAbs, activePower.coreColor, frameTick)
                drawPlasmaLightning(rightPalmAbs, orbAbs, activePower.coreColor, frameTick)
            }
        }

        // 2. Draw Hand tracking joint skeletons (MediaPipe style!)
        // Left hand skeleton
        drawHandJointSkeleton(
            wristAbs = Offset(leftPalmAbs.x, leftPalmAbs.y + 120f * (palmDistance * 1.5f).coerceIn(0.5f, 1.5f)),
            palmCenterAbs = leftPalmAbs,
            scale = (palmDistance * 1.2f).coerceIn(0.4f, 1.2f),
            isLeft = true,
            frameTick = frameTick,
            isActive = gestureState != GestureState.EXPLODED
        )

        // Right hand skeleton
        drawHandJointSkeleton(
            wristAbs = Offset(rightPalmAbs.x, rightPalmAbs.y + 120f * (palmDistance * 1.5f).coerceIn(0.5f, 1.5f)),
            palmCenterAbs = rightPalmAbs,
            scale = (palmDistance * 1.2f).coerceIn(0.4f, 1.2f),
            isLeft = false,
            frameTick = frameTick,
            isActive = gestureState != GestureState.EXPLODED
        )

        // 3. Draw Orbiting Ring visualizers (VFX rings spinning in 3D-like perspectives)
        if (gestureState == GestureState.STABLE || gestureState == GestureState.THROWING) {
            val baseRadius = (20.dp.toPx() + 30.dp.toPx() * palmDistance * 3f)
            val rotationAngle = frameTick * 2.5f
            
            // Orbiting Ring 1
            drawOrbitingRing(
                center = orbAbs,
                radiusX = baseRadius * 1.5f,
                radiusY = baseRadius * 0.4f,
                angleDegrees = 25f + sin(frameTick * 0.02f) * 10f,
                rotationProgress = rotationAngle,
                color = activePower.coreColor.copy(alpha = 0.7f),
                strokeWidth = 3f
            )

            // Orbiting Ring 2 (crossed direction)
            drawOrbitingRing(
                center = orbAbs,
                radiusX = baseRadius * 1.5f,
                radiusY = baseRadius * 0.4f,
                angleDegrees = -25f - cos(frameTick * 0.02f) * 10f,
                rotationProgress = rotationAngle + 180f,
                color = activePower.accentColor.copy(alpha = 0.6f),
                strokeWidth = 2f
            )
        }

        // 4. Draw Ambient float sparks & particles
        particles.forEach { p ->
            val pAbs = Offset(p.x * w, p.y * h)
            val alphaBlend = p.alpha * (powerLevel / 100f).coerceAtLeast(0.1f)
            drawCircle(
                color = activePower.coreColor.copy(alpha = alphaBlend),
                radius = p.size,
                center = pAbs
            )
        }

        sparks.forEach { s ->
            val sAbs = Offset(s.x * w, s.y * h)
            // Ember color blends from fire-white to red/orange for Fire Orb, or match colors for others
            val emberColor = when (activePower) {
                PowerType.FIRE_ORB -> {
                    if (s.age < s.maxAge * 0.3) Color.White
                    else if (s.age < s.maxAge * 0.7) activePower.accentColor
                    else activePower.coreColor
                }
                else -> activePower.coreColor
            }
            drawCircle(
                color = emberColor.copy(alpha = s.alpha),
                radius = s.size,
                center = sAbs
            )
        }

        // 5. Draw Core Energy Orb (The magical pulsing star center)
        if (gestureState != GestureState.EXPLODED && gestureState != GestureState.REFORMING) {
            val baseRadius = (15.dp.toPx() + 20.dp.toPx() * palmDistance * 3f) * (powerLevel / 100f)
            val pulseMultiplier = 1f + 0.08f * sin(frameTick * 0.12f)
            val finalRadius = baseRadius * pulseMultiplier

            val drawRadius = finalRadius.coerceAtLeast(1f)
            // Core center gradient
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White,
                        activePower.coreColor,
                        activePower.accentColor.copy(alpha = 0.2f),
                        Color.Transparent
                    ),
                    center = orbAbs,
                    radius = drawRadius
                ),
                center = orbAbs,
                radius = drawRadius
            )

            // Inner super-hot core
            drawCircle(
                color = Color.White.copy(alpha = 0.9f),
                center = orbAbs,
                radius = drawRadius * 0.35f
            )

            // Outer crackle flares
            for (i in 0..6) {
                val flareAngle = (frameTick * 0.05f + i * (2 * PI / 7)).toFloat()
                val length = finalRadius * (1.1f + 0.15f * sin(frameTick * 0.2f + i))
                drawLine(
                    color = activePower.coreColor.copy(alpha = 0.8f),
                    start = orbAbs,
                    end = Offset(orbAbs.x + cos(flareAngle) * length, orbAbs.y + sin(flareAngle) * length),
                    strokeWidth = 4f
                )
            }
        }

        // 6. Draw Shockwaves
        shockwaves.forEach { sw ->
            val rPx = sw.radius * w
            drawCircle(
                color = sw.color.copy(alpha = sw.alpha),
                center = Offset(sw.x * w, sw.y * h),
                radius = rPx,
                style = Stroke(width = 6f)
            )
        }

        // 7. Draw Explosion sparks (Launched blast impact!)
        explosions.forEach { ep ->
            val pAbs = Offset(ep.x * w, ep.y * h)
            // Gravitational impact sparkles blend colors dynamically
            val burstColor = if (ep.age % 4 == 0) Color.White else activePower.coreColor
            drawCircle(
                color = burstColor.copy(alpha = ep.alpha),
                radius = ep.size,
                center = pAbs
            )
        }
    }
}

// ==========================================
// DRAWING HELPERS (SKELETON & PLASMA)
// ==========================================

private fun DrawScope.drawPlasmaLightning(
    start: Offset,
    end: Offset,
    color: Color,
    frameTick: Int
) {
    val path = Path()
    path.moveTo(start.x, start.y)

    val segments = 8
    val dx = (end.x - start.x) / segments
    val dy = (end.y - start.y) / segments

    // Generate lightning zigzag points
    var currentX = start.x
    var currentY = start.y

    val rand = Random(frameTick.toLong())

    for (i in 1 until segments) {
        val targetX = start.x + dx * i
        val targetY = start.y + dy * i

        // Calculate normal vector for perpendicular displacement
        val nx = -dy
        val ny = dx
        val length = sqrt(nx * nx + ny * ny)
        val ux = if (length > 0) nx / length else 0f
        val uy = if (length > 0) ny / length else 0f

        // Random displacement offset
        val displacement = (rand.nextFloat() - 0.5f) * 45f
        currentX = targetX + ux * displacement
        currentY = targetY + uy * displacement

        path.lineTo(currentX, currentY)
    }

    path.lineTo(end.x, end.y)

    // Draw electrical bolt line
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = 4f, cap = StrokeCap.Round)
    )

    // Glow line (thicker, transparent)
    drawPath(
        path = path,
        color = color.copy(alpha = 0.35f),
        style = Stroke(width = 12f, cap = StrokeCap.Round)
    )
}

private fun DrawScope.drawOrbitingRing(
    center: Offset,
    radiusX: Float,
    radiusY: Float,
    angleDegrees: Float,
    rotationProgress: Float,
    color: Color,
    strokeWidth: Float
) {
    val path = Path()
    val segments = 36
    val angleRad = Math.toRadians(angleDegrees.toDouble()).toFloat()

    val cosAngle = cos(angleRad)
    val sinAngle = sin(angleRad)

    for (i in 0..segments) {
        val segmentAngle = Math.toRadians((i * (360f / segments) + rotationProgress).toDouble()).toFloat()
        // Standard ellipse equation
        val ex = radiusX * cos(segmentAngle)
        val ey = radiusY * sin(segmentAngle)

        // Rotate ellipse coordinate by tilt angle
        val rotatedX = center.x + (ex * cosAngle - ey * sinAngle)
        val rotatedY = center.y + (ex * sinAngle + ey * cosAngle)

        if (i == 0) {
            path.moveTo(rotatedX, rotatedY)
        } else {
            path.lineTo(rotatedX, rotatedY)
        }
    }

    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth)
    )
}

// MediaPipe Hand Landmark drawing matching specified colors per finger!
private fun DrawScope.drawHandJointSkeleton(
    wristAbs: Offset,
    palmCenterAbs: Offset,
    scale: Float,
    isLeft: Boolean,
    frameTick: Int,
    isActive: Boolean
) {
    if (!isActive) return

    val sideMult = if (isLeft) -1f else 1f
    val wiggle = 8f * sin(frameTick * 0.15f)

    // Standard MediaPipe color palette required
    val thumbColor = Color(0xFFFF3B30) // Red
    val indexColor = Color(0xFF30D5C8) // Cyan
    val middleColor = Color(0xFF007AFF) // Blue
    val ringColor = Color(0xFFFFD700) // Gold
    val pinkyColor = Color(0xFFAF52DE) // Purple

    val skeletonLineColor = Color(0x66FFFFFF)

    // 1. Map all 21 points based on structural skeleton kinematics
    val joints = Array(21) { Offset.Zero }
    
    // Joint 0: Wrist
    joints[0] = wristAbs

    // Knuckles baselines
    val indexBase = Offset(palmCenterAbs.x + 30f * sideMult * scale, palmCenterAbs.y - 15f * scale)
    val middleBase = Offset(palmCenterAbs.x + 5f * sideMult * scale, palmCenterAbs.y - 25f * scale)
    val ringBase = Offset(palmCenterAbs.x - 18f * sideMult * scale, palmCenterAbs.y - 20f * scale)
    val pinkyBase = Offset(palmCenterAbs.x - 38f * sideMult * scale, palmCenterAbs.y - 10f * scale)

    // Thumb (1..4)
    joints[1] = Offset(palmCenterAbs.x + 50f * sideMult * scale, palmCenterAbs.y + 40f * scale)
    joints[2] = Offset(palmCenterAbs.x + 85f * sideMult * scale, palmCenterAbs.y + 20f * scale)
    joints[3] = Offset(palmCenterAbs.x + 110f * sideMult * scale, palmCenterAbs.y - 5f * scale)
    joints[4] = Offset(palmCenterAbs.x + 130f * sideMult * scale + wiggle * 0.5f, palmCenterAbs.y - 25f * scale)

    // Index (5..8)
    joints[5] = indexBase
    joints[6] = Offset(indexBase.x + 10f * sideMult * scale, indexBase.y - 50f * scale)
    joints[7] = Offset(indexBase.x + 15f * sideMult * scale, indexBase.y - 85f * scale)
    joints[8] = Offset(indexBase.x + 18f * sideMult * scale + wiggle, indexBase.y - 110f * scale)

    // Middle (9..12)
    joints[9] = middleBase
    joints[10] = Offset(middleBase.x + 0f * scale, middleBase.y - 65f * scale)
    joints[11] = Offset(middleBase.x + 0f * scale, middleBase.y - 105f * scale)
    joints[12] = Offset(middleBase.x + 0f * scale + wiggle, middleBase.y - 135f * scale)

    // Ring (13..16)
    joints[13] = ringBase
    joints[14] = Offset(ringBase.x - 10f * sideMult * scale, ringBase.y - 55f * scale)
    joints[15] = Offset(ringBase.x - 15f * sideMult * scale, ringBase.y - 95f * scale)
    joints[16] = Offset(ringBase.x - 18f * sideMult * scale + wiggle, ringBase.y - 120f * scale)

    // Pinky (17..20)
    joints[17] = pinkyBase
    joints[18] = Offset(pinkyBase.x - 25f * sideMult * scale, pinkyBase.y - 45f * scale)
    joints[19] = Offset(pinkyBase.x - 35f * sideMult * scale, pinkyBase.y - 75f * scale)
    joints[20] = Offset(pinkyBase.x - 42f * sideMult * scale + wiggle, pinkyBase.y - 95f * scale)

    // 2. Draw connections (MediaPipe bone skeleton lines)
    fun drawBone(j1: Int, j2: Int) {
        drawLine(
            color = skeletonLineColor,
            start = joints[j1],
            end = joints[j2],
            strokeWidth = 3f,
            cap = StrokeCap.Round
        )
    }

    // Wrist to Knuckles & Palm perimeter
    drawBone(0, 1)
    drawBone(0, 17)
    drawBone(1, 2)
    
    // Knuckles line connection
    drawLine(color = skeletonLineColor, start = indexBase, end = middleBase, strokeWidth = 3f)
    drawLine(color = skeletonLineColor, start = middleBase, end = ringBase, strokeWidth = 3f)
    drawLine(color = skeletonLineColor, start = ringBase, end = pinkyBase, strokeWidth = 3f)
    drawLine(color = skeletonLineColor, start = pinkyBase, end = wristAbs, strokeWidth = 3f)
    drawLine(color = skeletonLineColor, start = indexBase, end = joints[1], strokeWidth = 3f)

    // Fingers connections
    for (i in 2..3) drawBone(i, i + 1) // Thumb
    for (i in 5..7) drawBone(i, i + 1) // Index
    for (i in 9..11) drawBone(i, i + 1) // Middle
    for (i in 13..15) drawBone(i, i + 1) // Ring
    for (i in 17..19) drawBone(i, i + 1) // Pinky

    // 3. Draw Joint Circles (Color-coded glowing landmarks!)
    fun drawJoint(index: Int, color: Color) {
        val center = joints[index]
        // Outer glowing shadow
        drawCircle(
            color = color.copy(alpha = 0.4f),
            radius = 12f * scale.coerceAtLeast(0.7f),
            center = center
        )
        // Inner sharp dot
        drawCircle(
            color = Color.White,
            radius = 4f * scale.coerceAtLeast(0.7f),
            center = center
        )
        // Accent stroke rim
        drawCircle(
            color = color,
            radius = 6f * scale.coerceAtLeast(0.7f),
            center = center,
            style = Stroke(width = 2f)
        )
    }

    // Wrist & Palm core
    drawJoint(0, Color.White)

    // Thumb (Red)
    for (i in 1..4) drawJoint(i, thumbColor)

    // Index (Cyan)
    for (i in 5..8) drawJoint(i, indexColor)

    // Middle (Blue)
    for (i in 9..12) drawJoint(i, middleColor)

    // Ring (Gold)
    for (i in 13..16) drawJoint(i, ringColor)

    // Pinky (Purple)
    for (i in 17..20) drawJoint(i, pinkyColor)
}

// ==========================================
// COMPONENT DESIGN (GLASSMORPHISM CARDS)
// ==========================================

@Composable
fun Modifier.glassmorphicBackground(): Modifier = this
    .background(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0x0EFFFFFF), Color(0x05FFFFFF))
        ),
        shape = RoundedCornerShape(16.dp)
    )
    .border(
        width = 1.dp,
        brush = Brush.linearGradient(
            colors = listOf(Color(0x1AFFFFFF), Color(0x08FFFFFF))
        ),
        shape = RoundedCornerShape(16.dp)
    )

@Composable
fun GlassCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassmorphicBackground()
            .padding(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF22D3EE),
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        HorizontalDivider(
            color = Color(0x1AFFFFFF),
            thickness = 1.dp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        content()
    }
}
