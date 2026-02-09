/**
 * Navigator Theme
 *
 * Modern UI styling for Navigator app.
 * Provides consistent visual styling across all activities.
 */
package com.continuum.navigator.core.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils

/**
 * Color palette for Navigator app.
 */
object NavigatorColors {
    // Primary colors (M3 Indigo)
    val primaryDark = Color.parseColor("#1A237E")      // Deep Indigo
    val primary = Color.parseColor("#3F51B5")          // Indigo
    val primaryLight = Color.parseColor("#7986CB")     // Light Indigo
    
    // Accent colors (M3 Cyan/Teal)
    val accent = Color.parseColor("#00E5FF")           // Bright Cyan
    val accentLight = Color.parseColor("#84FFFF")      // Light Cyan
    
    // Status colors
    val success = Color.parseColor("#43A047")          // Green
    val warning = Color.parseColor("#FB8C00")          // Orange
    val error = Color.parseColor("#E53935")            // Red
    val info = Color.parseColor("#1E88E5")             // Blue
    
    // Background colors
    val backgroundDark = Color.parseColor("#0F111A")   // Deep Midnight
    val backgroundCard = Color.parseColor("#1A1C2E")   // Dark Card
    val backgroundElevated = Color.parseColor("#252841") // Elevated surface
    
    // Text colors
    val textPrimary = Color.parseColor("#F5F5F7")
    val textSecondary = Color.parseColor("#A0A2B1")
    val textHint = Color.parseColor("#6B6D7A")
    
    // Gradient colors
    val gradientStart = Color.parseColor("#1A237E")
    val gradientEnd = Color.parseColor("#0F111A")
}

/**
 * UI styling utilities.
 */
object NavigatorTheme {
    
    /**
     * Create a gradient background drawable.
     */
    fun createGradientBackground(
        startColor: Int = NavigatorColors.gradientStart,
        endColor: Int = NavigatorColors.gradientEnd,
        cornerRadius: Float = 0f,
        orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TOP_BOTTOM
    ): GradientDrawable {
        return GradientDrawable(orientation, intArrayOf(startColor, endColor)).apply {
            if (cornerRadius > 0) {
                this.cornerRadius = cornerRadius
            }
        }
    }
    
    /**
     * Create a glassmorphic card background.
     */
    fun createGlassCard(context: Context, cornerRadiusDp: Float = 16f): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.argb(45, 255, 255, 255)) // Semi-transparent white
            cornerRadius = cornerRadiusDp * density
            setStroke((1 * density).toInt(), Color.argb(40, 255, 255, 255)) // Subtle border
        }
    }

    /**
     * Style a primary action button with a modern look.
     */
    fun stylePrimaryButton(button: Button, context: Context) {
        val density = context.resources.displayMetrics.density
        val cornerRadius = 12f * density
        
        val background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(NavigatorColors.primary, NavigatorColors.primaryDark)
        ).apply {
            this.cornerRadius = cornerRadius
        }
        
        button.apply {
            this.background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                RippleDrawable(ColorStateList.valueOf(Color.argb(50, 255, 255, 255)), background, null)
            } else background
            
            setTextColor(Color.WHITE)
            textSize = 15f
            isAllCaps = false
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding((24 * density).toInt(), (14 * density).toInt(), (24 * density).toInt(), (14 * density).toInt())
            elevation = 4 * density
        }
    }

    /**
     * Style an accent button.
     */
    fun styleAccentButton(button: Button, context: Context) {
        val density = context.resources.displayMetrics.density
        val cornerRadius = 12f * density
        
        val background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(NavigatorColors.accent, ColorUtils.blendARGB(NavigatorColors.accent, Color.BLACK, 0.2f))
        ).apply {
            this.cornerRadius = cornerRadius
        }
        
        button.apply {
            this.background = background
            setTextColor(NavigatorColors.backgroundDark)
            textSize = 15f
            isAllCaps = false
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding((24 * density).toInt(), (14 * density).toInt(), (24 * density).toInt(), (14 * density).toInt())
        }
    }

    /**
     * Style an outline button.
     */
    fun styleOutlineButton(button: Button, context: Context, color: Int = NavigatorColors.accent) {
        val density = context.resources.displayMetrics.density
        val cornerRadius = 12f * density
        
        val background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke((1.5f * density).toInt(), color)
            this.cornerRadius = cornerRadius
        }
        
        button.apply {
            this.background = background
            setTextColor(color)
            textSize = 14f
            isAllCaps = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
        }
    }

    /**
     * Create a glossy card background with a glow.
     */
    fun createGlossyCardBackground(context: Context): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor("#2A2D3E"),
                Color.parseColor("#1A1C2E")
            )
        ).apply {
            cornerRadius = 16f * density
            setStroke((1 * density).toInt(), Color.argb(30, 255, 255, 255))
        }
    }

    fun applyDarkTheme(activity: Activity, rootView: View) {
        rootView.background = createGradientBackground()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            activity.window.statusBarColor = NavigatorColors.primaryDark
            activity.window.navigationBarColor = NavigatorColors.backgroundDark
        }
    }

    fun createInfoPanelBackground(context: Context): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(Color.argb(200, 15, 17, 26))
            cornerRadius = 16f * density
            setStroke((1 * density).toInt(), Color.argb(40, 255, 255, 255))
        }
    }
}

fun View.setMargins(left: Int, top: Int, right: Int, bottom: Int) {
    val params = layoutParams as? LinearLayout.LayoutParams ?: return
    params.setMargins(left, top, right, bottom)
    layoutParams = params
}
