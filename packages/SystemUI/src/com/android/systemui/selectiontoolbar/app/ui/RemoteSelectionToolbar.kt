/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.selectiontoolbar.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Handler
import android.os.Looper
import android.service.selectiontoolbar.SelectionToolbarRenderService.OnPasteActionCallback
import android.service.selectiontoolbar.SelectionToolbarRenderService.RemoteCallbackWrapper
import android.service.selectiontoolbar.SelectionToolbarRenderService.TransferTouchListener
import android.text.TextUtils
import android.util.Log
import android.util.Size
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceControlViewHost
import android.view.SurfaceControlViewHost.SurfacePackage
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.AnimationUtils
import android.view.animation.Interpolator
import android.view.animation.Transformation
import android.view.selectiontoolbar.ShowInfo
import android.view.selectiontoolbar.ToolbarMenuItem
import android.view.selectiontoolbar.WidgetInfo
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.window.InputTransferToken
import com.android.internal.R
import com.android.internal.util.Preconditions
import com.android.internal.widget.floatingtoolbar.FloatingToolbar
import java.io.PrintWriter
import java.util.Objects
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * This class is responsible for rendering/animation of the selection toolbar in the remote system
 * process. It holds 2 panels (i.e. main panel and overflow panel) and an overflow button to
 * transition between panels.
 *
 * @hide
 */
// TODO(b/215497659): share code with LocalFloatingToolbarPopup
class RemoteSelectionToolbar(
    private val hostUid: Int,
    baseContext: Context,
    showInfo: ShowInfo,
    private val callbackWrapper: RemoteCallbackWrapper,
    transferTouchListener: TransferTouchListener,
    private val onPasteActionCallback: OnPasteActionCallback,
) {
    private val context = wrapContext(baseContext, showInfo)

    private val handler =
        Handler(
            Looper.myLooper()
                ?: throw IllegalStateException(
                    "RemoteSelectionToolbar must be created on a looper thread"
                )
        )
    private val hostInputToken = InputTransferToken(showInfo.hostInputToken)

    /* View components */
    private val contentContainer =
        createContentContainer(context).apply {
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateFloatingToolbarRootContentRect()
            }
        } // holds all contents.
    // The input is first hit tested for the embedded window, so we need this here for the
    // input to miss this embedded window. Then on the client popup window we need the same
    // touchable region again to avoid the input hitting the popup window after it misses
    // the embedded window.
    private val insetsComputer =
        ViewTreeObserver.OnComputeInternalInsetsListener { info ->
            info.contentInsets.setEmpty()
            info.visibleInsets.setEmpty()
            val mainPanelSize = mainPanelSize ?: return@OnComputeInternalInsetsListener
            val width: Int
            val height: Int
            if (isOverflowOpen && overflowPanelSize != null) {
                width = overflowPanelSize!!.width
                height = overflowPanelSize!!.height
            } else {
                width = mainPanelSize.width
                height = mainPanelSize.height
            }
            val x = contentContainer.x.toInt()
            val y = contentContainer.y.toInt()
            info.touchableRegion.set(x, y, x + width, y + height)
            info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
        }
    private val contentHolder =
        FrameLayout(context).apply {
            addView(contentContainer)
            addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        v.viewTreeObserver.removeOnComputeInternalInsetsListener(insetsComputer)
                        v.viewTreeObserver.addOnComputeInternalInsetsListener(insetsComputer)
                    }

                    override fun onViewDetachedFromWindow(v: View) {
                        v.viewTreeObserver.removeOnComputeInternalInsetsListener(insetsComputer)
                    }
                }
            )
        }

    /* Margins between the popup window and its content. */
    private val marginHorizontal =
        context.resources.getDimensionPixelSize(R.dimen.floating_toolbar_horizontal_margin)
    private val marginVertical =
        context.resources.getDimensionPixelSize(R.dimen.floating_toolbar_vertical_margin)
    private val lineHeight =
        context.resources.getDimensionPixelSize(R.dimen.floating_toolbar_height)
    private val iconTextSpacing =
        context.resources.getDimensionPixelSize(R.dimen.floating_toolbar_icon_text_spacing)

    /* Animation interpolators. */
    private val logAccelerateInterpolator = LogAccelerateInterpolator()
    private val fastOutSlowInInterpolator =
        AnimationUtils.loadInterpolator(context, android.R.interpolator.fast_out_slow_in)
    private val linearOutSlowInInterpolator =
        AnimationUtils.loadInterpolator(context, android.R.interpolator.linear_out_slow_in)
    private val fastOutLinearInInterpolator =
        AnimationUtils.loadInterpolator(context, android.R.interpolator.fast_out_linear_in)

    /* overflow button drawables. */
    // Drawables. Needed for views.
    private val arrow =
        context.resources.getDrawable(R.drawable.ft_avd_tooverflow, context.theme).apply {
            isAutoMirrored = true
        }
    private val overflow =
        context.resources.getDrawable(R.drawable.ft_avd_toarrow, context.theme).apply {
            isAutoMirrored = true
        }
    private val toArrow =
        context.resources.getDrawable(R.drawable.ft_avd_toarrow_animation, context.theme).apply {
            isAutoMirrored = true
        } as AnimatedVectorDrawable
    private val toOverflow =
        context.resources.getDrawable(R.drawable.ft_avd_tooverflow_animation, context.theme).apply {
            isAutoMirrored = true
        } as AnimatedVectorDrawable

    // Views.
    private val overflowButton = createOverflowButton() // opens/closes the overflow.
    private val overflowButtonSize = measure(overflowButton)
    private val mainPanel = createMainPanel() // holds menu items that are initially displayed.
    private val overflowPanelViewHelper = OverflowPanelViewHelper(context, iconTextSpacing)
    private val overflowPanel = createOverflowPanel()

    /* Animations. */

    private val overflowAnimationListener = createOverflowAnimationListener()
    private val openOverflowAnimation =
        AnimationSet(true).apply { setAnimationListener(overflowAnimationListener) }
    private val closeOverflowAnimation =
        AnimationSet(true).apply { setAnimationListener(overflowAnimationListener) }

    private val showAnimation = createEnterAnimation(contentContainer)
    private val delayedHideAnimation =
        createExitAnimation(
            contentContainer,
            150, // startDelay
            object : AnimatorListenerAdapter() {
                private var mCanceled = false

                override fun onAnimationCancel(animation: Animator) {
                    mCanceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (mCanceled) {
                        mCanceled = false
                        return
                    }
                    releaseSurfaceControlViewHost()
                    callbackWrapper.onInvisible()
                }
            },
        )
    private val immediateHideAnimation =
        createExitAnimation(
            contentContainer,
            0, // startDelay
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    releaseSurfaceControlViewHost()
                    callbackWrapper.onInvisible()
                }
            },
        )

    /* Menu items and click listeners */
    private val menuItemButtonOnClickListener =
        View.OnClickListener { v: View ->
            val tag = v.tag
            if (tag is ToolbarMenuItem) {
                dispatchMenuItemClick(tag)
            }
        }

    private fun dispatchMenuItemClick(menuItem: ToolbarMenuItem) {
        // Authorization is synchronous. Keep it on the renderer thread and ordered before the app
        // callback because the app may read the clipboard as soon as it handles the click.
        handler.post {
            if (SecurePasteToolbar.isPasteAction(menuItem)) {
                onPasteActionCallback.onPasteAction(hostUid, hostInputToken.token)
            }
            callbackWrapper.onMenuItemClicked(menuItem.itemIndex)
        }
    }

    private val viewPortOnScreen = Rect() // portion of screen we can draw in.

    private var popupWidth = 0
    private var popupHeight = 0

    // Coordinates to show the toolbar relative to the specified view port
    private val relativeCoordsForToolbar = Point()
    private var sequenceNumber = 0
    private var menuItems: MutableList<ToolbarMenuItem>? = null
    private var surfaceControlViewHost: SurfaceControlViewHost? = null
    private var surfacePackage: SurfacePackage? = null

    /** @see preparePopupContent */
    private val preparePopupContentRTLHelper = {
        setPanelsStatesAtRestingPosition()
        contentContainer.alpha = 1f
    }

    // Tracks this selection toolbar state.
    private var state: Int = TOOLBAR_STATE_DISMISSED

    private var overflowPanelSize: Size? = null // Should be null when there is no overflow.
    private var mainPanelSize: Size? = null

    private var openOverflowUpwards = false // Whether the overflow opens upwards or downwards.
    private var isOverflowOpen = false

    private var transitionDurationScale = 0 // Used to scale the toolbar transition duration.

    private val previousContentRect = Rect()

    private val tempContentRect = Rect()
    private val tempContentRectForRoot = Rect()
    private val tempCoords = IntArray(2)

    private fun updateFloatingToolbarRootContentRect() {
        if (surfaceControlViewHost == null) {
            return
        }
        contentContainer.getLocationOnScreen(tempCoords)
        val contentLeft = tempCoords[0]
        val contentTop = tempCoords[1]
        tempContentRectForRoot.set(
            contentLeft,
            contentTop,
            contentLeft + contentContainer.width,
            contentTop + contentContainer.height,
        )
    }

    private fun createWidgetInfo(): WidgetInfo {
        tempContentRect.set(
            relativeCoordsForToolbar.x,
            relativeCoordsForToolbar.y,
            relativeCoordsForToolbar.x + popupWidth,
            relativeCoordsForToolbar.y + popupHeight,
        )
        val widgetInfo = WidgetInfo()
        widgetInfo.sequenceNumber = sequenceNumber
        widgetInfo.contentRect = tempContentRect
        val width: Int
        val height: Int
        if (isOverflowOpen) {
            width = overflowPanelSize!!.width
            height = overflowPanelSize!!.height
        } else {
            width = mainPanelSize!!.width
            height = mainPanelSize!!.height
        }
        widgetInfo.touchableRegion =
            Region(
                relativeCoordsForToolbar.x + contentContainer.x.toInt(),
                relativeCoordsForToolbar.y + contentContainer.y.toInt(),
                relativeCoordsForToolbar.x + contentContainer.x.toInt() + width,
                relativeCoordsForToolbar.y + contentContainer.y.toInt() + height,
            )
        widgetInfo.surfacePackage = getSurfacePackage()
        return widgetInfo
    }

    private fun getSurfacePackage(): SurfacePackage {
        if (surfaceControlViewHost == null) {
            check(handler.looper.thread === Thread.currentThread()) {
                ("UI operations must be called on the same thread as the constructor." +
                    "thisThread = " +
                    Thread.currentThread() +
                    " handlerThread = " +
                    handler.looper.thread)
            }
            surfaceControlViewHost =
                SurfaceControlViewHost(
                    context,
                    context.display,
                    hostInputToken,
                    "RemoteSelectionToolbar",
                )
            surfaceControlViewHost!!.setView(contentHolder, popupWidth, popupHeight)
        }
        if (surfacePackage == null) {
            surfacePackage = surfaceControlViewHost!!.surfacePackage
        }
        return surfacePackage!!
    }

    private fun releaseSurfaceControlViewHost() {
        contentContainer.removeAllViews()
        surfaceControlViewHost?.release()
        surfaceControlViewHost = null
        surfacePackage = null
    }

    private fun layoutMenuItems(menuItems: List<ToolbarMenuItem>, suggestedWidth: Int) {
        cancelOverflowAnimations()
        clearPanels()

        layoutMainPanelItems(menuItems, getAdjustedToolbarWidth(suggestedWidth)).let {
            if (it.isNotEmpty()) {
                // Add remaining items to the overflow.
                layoutOverflowPanelItems(it)
            }
        }
        updatePopupSize()
    }

    /** Show the specified selection toolbar. */
    fun show(showInfo: ShowInfo) {
        debugLog("show() for $showInfo")

        sequenceNumber = showInfo.sequenceNumber
        menuItems = transformMenuItems(context, showInfo.menuItems)
        viewPortOnScreen.set(showInfo.viewPortOnScreen)

        if (showInfo.layoutRequired) {
            layoutMenuItems(menuItems!!, showInfo.suggestedWidth)
        }

        if (this.isHidden) {
            cancelDismissAndHideAnimations()
        }
        cancelOverflowAnimations()
        refreshCoordinatesAndOverflowDirection(showInfo.contentRect)
        preparePopupContent()

        if (!this.isShowing) {
            showAnimation.start()
            callbackWrapper.onShown(createWidgetInfo())
        } else {
            callbackWrapper.onWidgetUpdated(createWidgetInfo())
        }

        surfaceControlViewHost!!.relayout(popupWidth, popupHeight)

        state = TOOLBAR_STATE_SHOWN
        // TODO(b/215681595): Use Choreographer to coordinate for show between different thread
        previousContentRect.set(showInfo.contentRect)
    }

    /** Dismiss the specified selection toolbar. */
    fun dismiss(uid: Int) {
        debugLog("dismiss for uid: $uid")
        if (state == TOOLBAR_STATE_DISMISSED) {
            return
        }

        if (!immediateHideAnimation.isStarted) {
            delayedHideAnimation.start()
        }
        state = TOOLBAR_STATE_DISMISSED
    }

    /** Hide the specified selection toolbar. */
    fun hide(uid: Int) {
        debugLog("hide for uid: $uid")
        if (!this.isShowing) {
            return
        }
        delayedHideAnimation.cancel()
        immediateHideAnimation.start()
        state = TOOLBAR_STATE_HIDDEN
    }

    val isShowing: Boolean
        get() = state == TOOLBAR_STATE_SHOWN

    val isHidden: Boolean
        get() = state == TOOLBAR_STATE_HIDDEN

    private fun refreshCoordinatesAndOverflowDirection(contentRectOnScreen: Rect) {
        val x =
            if (popupWidth > viewPortOnScreen.width()) {
                // Not enough space - prefer to position as far left as possible
                viewPortOnScreen.left
            } else {
                // Initialize x ensuring that the toolbar isn't rendered behind the system bar
                // insets
                (contentRectOnScreen.centerX() - popupWidth / 2).coerceIn(
                    viewPortOnScreen.left,
                    viewPortOnScreen.right - popupWidth,
                )
            }

        val availableHeightAboveContent = contentRectOnScreen.top - viewPortOnScreen.top
        val availableHeightBelowContent = viewPortOnScreen.bottom - contentRectOnScreen.bottom

        val margin = 2 * marginVertical
        val toolbarHeightWithVerticalMargin = lineHeight + margin

        val y =
            if (hasOverflow()) {
                getYCoordinateAndRefreshOverflowDirection(
                    contentRectOnScreen,
                    toolbarHeightWithVerticalMargin,
                    availableHeightAboveContent,
                    availableHeightBelowContent,
                    margin,
                )
            } else {
                getYCoordinateWithNoOverflow(
                    contentRectOnScreen,
                    toolbarHeightWithVerticalMargin,
                    availableHeightAboveContent,
                    availableHeightBelowContent,
                )
            }
        relativeCoordsForToolbar.set(x, y)
    }

    private fun getYCoordinateAndRefreshOverflowDirection(
        contentRectOnScreen: Rect,
        toolbarHeightWithVerticalMargin: Int,
        availableHeightAboveContent: Int,
        availableHeightBelowContent: Int,
        margin: Int,
    ): Int {
        // Has an overflow.
        val minimumOverflowHeightWithMargin = calculateOverflowHeight(MIN_OVERFLOW_SIZE) + margin
        val availableHeightThroughContentDown =
            (viewPortOnScreen.bottom - contentRectOnScreen.top + toolbarHeightWithVerticalMargin)
        val availableHeightThroughContentUp =
            (contentRectOnScreen.bottom - viewPortOnScreen.top + toolbarHeightWithVerticalMargin)

        return when {
            availableHeightAboveContent >= minimumOverflowHeightWithMargin -> {
                // There is enough space at the top of the content rect for the overflow.
                // Position above and open upwards.
                updateOverflowHeight(availableHeightAboveContent - margin)
                openOverflowUpwards = true
                contentRectOnScreen.top - popupHeight
            }

            availableHeightAboveContent >= toolbarHeightWithVerticalMargin &&
                availableHeightThroughContentDown >= minimumOverflowHeightWithMargin -> {
                // There is enough space at the top of the content rect for the main panel
                // but not the overflow.
                // Position above but open downwards.
                updateOverflowHeight(availableHeightThroughContentDown - margin)
                openOverflowUpwards = false
                contentRectOnScreen.top - toolbarHeightWithVerticalMargin
            }

            availableHeightBelowContent >= minimumOverflowHeightWithMargin -> {
                // There is enough space at the bottom of the content rect for the overflow.
                // Position below and open downwards.
                updateOverflowHeight(availableHeightBelowContent - margin)
                openOverflowUpwards = false
                contentRectOnScreen.bottom
            }

            availableHeightBelowContent >= toolbarHeightWithVerticalMargin &&
                viewPortOnScreen.height() >= minimumOverflowHeightWithMargin -> {
                // There is enough space at the bottom of the content rect for the main
                // panel
                // but not the overflow.
                // Position below but open upwards.
                updateOverflowHeight(availableHeightThroughContentUp - margin)
                openOverflowUpwards = true
                contentRectOnScreen.bottom + toolbarHeightWithVerticalMargin - popupHeight
            }

            else -> {
                // Not enough space.
                // Position at the top of the view port and open downwards.
                updateOverflowHeight(viewPortOnScreen.height() - margin)
                openOverflowUpwards = false
                viewPortOnScreen.top
            }
        }
    }

    private fun getYCoordinateWithNoOverflow(
        contentRectOnScreen: Rect,
        toolbarHeightWithVerticalMargin: Int,
        availableHeightAboveContent: Int,
        availableHeightBelowContent: Int,
    ): Int =
        when {
            availableHeightAboveContent >= toolbarHeightWithVerticalMargin -> {
                // There is enough space at the top of the content.
                contentRectOnScreen.top - toolbarHeightWithVerticalMargin
            }

            availableHeightBelowContent >= toolbarHeightWithVerticalMargin -> {
                // There is enough space at the bottom of the content.
                contentRectOnScreen.bottom
            }

            availableHeightBelowContent >= lineHeight -> {
                // Just enough space to fit the toolbar with no vertical margins.
                contentRectOnScreen.bottom - marginVertical
            }

            else -> {
                // Not enough space. Prefer to position as high as possible.
                max(viewPortOnScreen.top, contentRectOnScreen.top - toolbarHeightWithVerticalMargin)
            }
        }

    private fun cancelDismissAndHideAnimations() {
        delayedHideAnimation.cancel()
        immediateHideAnimation.cancel()
    }

    private fun cancelOverflowAnimations() {
        contentContainer.clearAnimation()
        mainPanel.animate().cancel()
        overflowPanel.animate().cancel()
        toArrow.stop()
        toOverflow.stop()
    }

    private fun openOverflow() {
        val targetWidth = overflowPanelSize!!.width
        val targetHeight = overflowPanelSize!!.height
        val startWidth = contentContainer.width
        val startHeight = contentContainer.height
        val startY = contentContainer.y
        val left = contentContainer.x
        val right = left + contentContainer.width
        val widthAnimation: Animation =
            object : Animation() {
                override fun applyTransformation(interpolatedTime: Float, t: Transformation) {
                    val deltaWidth = (interpolatedTime * (targetWidth - startWidth)).toInt()
                    setWidth(contentContainer, startWidth + deltaWidth)
                    if (isInRtlMode()) {
                        contentContainer.x = left

                        // Lock the panels in place.
                        mainPanel.x = 0f
                        overflowPanel.x = 0f
                    } else {
                        contentContainer.x = right - contentContainer.width

                        // Offset the panels' positions so they look like they're locked in place
                        // on the screen.
                        mainPanel.x = (contentContainer.width - startWidth).toFloat()
                        overflowPanel.x = (contentContainer.width - targetWidth).toFloat()
                    }
                }
            }
        val heightAnimation: Animation =
            object : Animation() {
                override fun applyTransformation(interpolatedTime: Float, t: Transformation) {
                    val deltaHeight = (interpolatedTime * (targetHeight - startHeight)).toInt()
                    setHeight(contentContainer, startHeight + deltaHeight)
                    if (openOverflowUpwards) {
                        contentContainer.y = startY - (contentContainer.height - startHeight)
                        positionContentYCoordinatesIfOpeningOverflowUpwards()
                    }
                }
            }
        val overflowButtonStartX = overflowButton.x
        val overflowButtonTargetX =
            if (isInRtlMode()) overflowButtonStartX + targetWidth - overflowButton.width
            else overflowButtonStartX - targetWidth + overflowButton.width
        val overflowButtonAnimation: Animation =
            object : Animation() {
                override fun applyTransformation(interpolatedTime: Float, t: Transformation) {
                    val overflowButtonX =
                        (overflowButtonStartX +
                            interpolatedTime * (overflowButtonTargetX - overflowButtonStartX))
                    val deltaContainerWidth =
                        (if (isInRtlMode()) 0 else contentContainer.width - startWidth).toFloat()
                    val actualOverflowButtonX = overflowButtonX + deltaContainerWidth
                    overflowButton.x = actualOverflowButtonX
                    updateFloatingToolbarRootContentRect()
                }
            }
        widthAnimation.interpolator = logAccelerateInterpolator
        widthAnimation.duration = this.animationDuration.toLong()
        heightAnimation.interpolator = fastOutSlowInInterpolator
        heightAnimation.duration = this.animationDuration.toLong()
        overflowButtonAnimation.interpolator = fastOutSlowInInterpolator
        overflowButtonAnimation.duration = this.animationDuration.toLong()
        openOverflowAnimation.animations.clear()
        openOverflowAnimation.addAnimation(widthAnimation)
        openOverflowAnimation.addAnimation(heightAnimation)
        openOverflowAnimation.addAnimation(overflowButtonAnimation)
        contentContainer.startAnimation(openOverflowAnimation)
        isOverflowOpen = true
        mainPanel
            .animate()
            .alpha(0f)
            .withLayer()
            .setInterpolator(linearOutSlowInInterpolator)
            .setDuration(250)
            .start()
        overflowPanel.alpha = 1f // fadeIn in 0ms.
    }

    private fun closeOverflow() {
        val targetWidth = mainPanelSize!!.width
        val startWidth = contentContainer.width
        val left = contentContainer.x
        val right = left + contentContainer.width
        val widthAnimation: Animation =
            object : Animation() {
                override fun applyTransformation(interpolatedTime: Float, t: Transformation) {
                    val deltaWidth = (interpolatedTime * (targetWidth - startWidth)).toInt()
                    setWidth(contentContainer, startWidth + deltaWidth)
                    if (isInRtlMode()) {
                        contentContainer.x = left

                        // Lock the panels in place.
                        mainPanel.x = 0f
                        overflowPanel.x = 0f
                    } else {
                        contentContainer.x = right - contentContainer.width

                        // Offset the panels' positions so they look like they're locked in place
                        // on the screen.
                        mainPanel.x = (contentContainer.width - targetWidth).toFloat()
                        overflowPanel.x = (contentContainer.width - startWidth).toFloat()
                    }
                }
            }
        val targetHeight = mainPanelSize!!.height
        val startHeight = contentContainer.height
        val bottom = contentContainer.y + contentContainer.height
        val heightAnimation: Animation =
            object : Animation() {
                override fun applyTransformation(interpolatedTime: Float, t: Transformation) {
                    val deltaHeight = (interpolatedTime * (targetHeight - startHeight)).toInt()
                    setHeight(contentContainer, startHeight + deltaHeight)
                    if (openOverflowUpwards) {
                        contentContainer.y = bottom - contentContainer.height
                        positionContentYCoordinatesIfOpeningOverflowUpwards()
                    }
                }
            }
        val overflowButtonStartX = overflowButton.x
        val overflowButtonTargetX =
            if (isInRtlMode()) overflowButtonStartX - startWidth + overflowButton.width
            else overflowButtonStartX + startWidth - overflowButton.width
        val overflowButtonAnimation: Animation =
            object : Animation() {
                override fun applyTransformation(interpolatedTime: Float, t: Transformation) {
                    val overflowButtonX =
                        (overflowButtonStartX +
                            interpolatedTime * (overflowButtonTargetX - overflowButtonStartX))
                    val deltaContainerWidth =
                        (if (isInRtlMode()) 0 else contentContainer.width - startWidth).toFloat()
                    val actualOverflowButtonX = overflowButtonX + deltaContainerWidth
                    overflowButton.x = actualOverflowButtonX
                    updateFloatingToolbarRootContentRect()
                }
            }
        widthAnimation.interpolator = fastOutSlowInInterpolator
        widthAnimation.duration = this.animationDuration.toLong()
        heightAnimation.interpolator = logAccelerateInterpolator
        heightAnimation.duration = this.animationDuration.toLong()
        overflowButtonAnimation.interpolator = fastOutSlowInInterpolator
        overflowButtonAnimation.duration = this.animationDuration.toLong()
        closeOverflowAnimation.animations.clear()
        closeOverflowAnimation.addAnimation(widthAnimation)
        closeOverflowAnimation.addAnimation(heightAnimation)
        closeOverflowAnimation.addAnimation(overflowButtonAnimation)
        contentContainer.startAnimation(closeOverflowAnimation)
        isOverflowOpen = false
        mainPanel
            .animate()
            .alpha(1f)
            .withLayer()
            .setInterpolator(fastOutLinearInInterpolator)
            .setDuration(100)
            .start()
        overflowPanel
            .animate()
            .alpha(0f)
            .withLayer()
            .setInterpolator(linearOutSlowInInterpolator)
            .setDuration(150)
            .start()
    }

    /**
     * Defines the position of the floating toolbar popup panels when transition animation has
     * stopped.
     */
    private fun setPanelsStatesAtRestingPosition() {
        overflowButton.isEnabled = true
        overflowPanel.awakenScrollBars()

        if (isOverflowOpen) {
            // Set open state.
            val containerSize = overflowPanelSize
            setSize(contentContainer, containerSize!!)
            mainPanel.alpha = 0f
            mainPanel.visibility = View.INVISIBLE
            overflowPanel.alpha = 1f
            overflowPanel.visibility = View.VISIBLE
            overflowButton.setImageDrawable(arrow)
            overflowButton.contentDescription =
                context.getString(R.string.floating_toolbar_close_overflow_description)

            // Update x-coordinates depending on RTL state.
            if (isInRtlMode()) {
                contentContainer.x = marginHorizontal.toFloat() // align left
                mainPanel.x = 0f // align left
                overflowButton.x = (containerSize.width - overflowButtonSize.width).toFloat()
                overflowPanel.x = 0f // align left
            } else {
                contentContainer.x = (popupWidth - containerSize.width - marginHorizontal).toFloat()
                mainPanel.x = -contentContainer.x // align right
                overflowButton.x = 0f // align left
                overflowPanel.x = 0f // align left
            }

            // Update y-coordinates depending on overflow's open direction.
            if (openOverflowUpwards) {
                contentContainer.y = marginVertical.toFloat() // align top
                mainPanel.y = (containerSize.height - contentContainer.height).toFloat()
                overflowButton.y = (containerSize.height - overflowButtonSize.height).toFloat()
                overflowPanel.y = 0f // align top
            } else {
                // opens downwards.
                contentContainer.y = marginVertical.toFloat() // align top
                mainPanel.y = 0f // align top
                overflowButton.y = 0f // align top
                overflowPanel.y = overflowButtonSize.height.toFloat() // align bottom
            }
        } else {
            // Overflow not open. Set closed state.
            val containerSize = mainPanelSize
            setSize(contentContainer, containerSize!!)
            mainPanel.alpha = 1f
            mainPanel.visibility = View.VISIBLE
            overflowPanel.alpha = 0f
            overflowPanel.visibility = View.INVISIBLE
            overflowButton.setImageDrawable(overflow)
            overflowButton.contentDescription =
                context.getString(R.string.floating_toolbar_open_overflow_description)

            if (hasOverflow()) {
                // Update x-coordinates depending on RTL state.
                if (isInRtlMode()) {
                    contentContainer.x = marginHorizontal.toFloat() // align left
                    mainPanel.x = 0f // align left
                    overflowButton.x = 0f // align left
                    overflowPanel.x = 0f // align left
                } else {
                    contentContainer.x =
                        (popupWidth - containerSize.width - marginHorizontal).toFloat()
                    mainPanel.x = 0f // align left
                    overflowButton.x = (containerSize.width - overflowButtonSize.width).toFloat()
                    overflowPanel.x = (containerSize.width - overflowPanelSize!!.width).toFloat()
                }

                // Update y-coordinates depending on overflow's open direction.
                if (openOverflowUpwards) {
                    contentContainer.y =
                        (marginVertical + overflowPanelSize!!.height - containerSize.height)
                            .toFloat()
                    mainPanel.y = 0f // align top
                    overflowButton.y = 0f // align top
                    overflowPanel.y = (containerSize.height - overflowPanelSize!!.height).toFloat()
                } else {
                    // opens downwards.
                    contentContainer.y = marginVertical.toFloat() // align top
                    mainPanel.y = 0f // align top
                    overflowButton.y = 0f // align top
                    overflowPanel.y = overflowButtonSize.height.toFloat() // align bottom
                }
            } else {
                // No overflow.
                contentContainer.x = marginHorizontal.toFloat() // align left
                contentContainer.y = marginVertical.toFloat() // align top
                mainPanel.x = 0f // align left
                mainPanel.y = 0f // align top
            }
        }
    }

    private fun updateOverflowHeight(suggestedHeight: Int) {
        if (hasOverflow()) {
            val maxItemSize = (suggestedHeight - overflowButtonSize.height) / lineHeight
            val newHeight = calculateOverflowHeight(maxItemSize)
            if (overflowPanelSize!!.height != newHeight) {
                overflowPanelSize = Size(overflowPanelSize!!.width, newHeight)
            }
            setSize(overflowPanel, overflowPanelSize!!)
            if (isOverflowOpen) {
                setSize(contentContainer, overflowPanelSize!!)
                if (openOverflowUpwards) {
                    val deltaHeight = overflowPanelSize!!.height - newHeight
                    contentContainer.y += deltaHeight
                    overflowButton.y -= deltaHeight
                }
            } else {
                setSize(contentContainer, mainPanelSize!!)
            }
            updatePopupSize()
        }
    }

    private fun updatePopupSize() {
        var width = 0
        var height = 0
        if (mainPanelSize != null) {
            width = max(width, mainPanelSize!!.width)
            height = max(height, mainPanelSize!!.height)
        }
        if (overflowPanelSize != null) {
            width = max(width, overflowPanelSize!!.width)
            height = max(height, overflowPanelSize!!.height)
        }

        popupWidth = width + marginHorizontal * 2
        popupHeight = height + marginVertical * 2
        maybeComputeTransitionDurationScale()
    }

    private fun getAdjustedToolbarWidth(suggestedWidth: Int): Int {
        var width = suggestedWidth
        val maximumWidth =
            viewPortOnScreen.width() -
                2 *
                    context.resources.getDimensionPixelSize(
                        R.dimen.floating_toolbar_horizontal_margin
                    )
        if (width <= 0) {
            width =
                context.resources.getDimensionPixelSize(R.dimen.floating_toolbar_preferred_width)
        }
        return min(width, maximumWidth)
    }

    private fun isInRtlMode(): Boolean =
        // TODO STOPSHIP b/411457891 the context might not have the right information
        context.applicationInfo.hasRtlSupport() &&
            (context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)

    private fun hasOverflow(): Boolean {
        return overflowPanelSize != null
    }

    /**
     * Fits as many menu items in the main panel and returns a list of the menu items that were not
     * fit in.
     *
     * @return The menu items that are not included in this main panel.
     */
    private fun layoutMainPanelItems(
        menuItems: List<ToolbarMenuItem>,
        toolbarWidth: Int,
    ): List<ToolbarMenuItem> {
        val remainingMenuItems = mutableListOf<ToolbarMenuItem>()
        // add the overflow menu items to the end of the remainingMenuItems list.
        val overflowMenuItems = mutableListOf<ToolbarMenuItem>()
        for (menuItem in menuItems) {
            if (
                menuItem.itemId != android.R.id.textAssist &&
                    menuItem.priority == ToolbarMenuItem.PRIORITY_OVERFLOW
            ) {
                overflowMenuItems.add(menuItem)
            } else {
                remainingMenuItems.add(menuItem)
            }
        }
        remainingMenuItems.addAll(overflowMenuItems)

        mainPanel.removeAllViews()
        mainPanel.setPaddingRelative(0, 0, 0, 0)

        var availableWidth = toolbarWidth
        var isFirstItem = true
        while (!remainingMenuItems.isEmpty()) {
            val menuItem = remainingMenuItems[0]
            // if this is the first item, regardless of requiresOverflow(), it should be
            // displayed on the main panel. Otherwise all items including this one will be
            // overflow items, and should be displayed in overflow panel.
            if (!isFirstItem && menuItem.priority == ToolbarMenuItem.PRIORITY_OVERFLOW) {
                break
            }
            val showIcon = isFirstItem && menuItem.itemId == R.id.textAssist
            val menuItemButton: View =
                createMenuItemButton(context, menuItem, iconTextSpacing, showIcon)
            if (!showIcon && menuItemButton is LinearLayout) {
                menuItemButton.gravity = Gravity.CENTER
            }
            // Adding additional start padding for the first button to even out button spacing.
            if (isFirstItem) {
                menuItemButton.setPaddingRelative(
                    (1.5 * menuItemButton.paddingStart).toInt(),
                    menuItemButton.paddingTop,
                    menuItemButton.paddingEnd,
                    menuItemButton.paddingBottom,
                )
            }
            // Adding additional end padding for the last button to even out button spacing.
            val isLastItem = remainingMenuItems.size == 1
            if (isLastItem) {
                menuItemButton.setPaddingRelative(
                    menuItemButton.paddingStart,
                    menuItemButton.paddingTop,
                    (1.5 * menuItemButton.paddingEnd).toInt(),
                    menuItemButton.paddingBottom,
                )
            }
            menuItemButton.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
            val menuItemButtonWidth = min(menuItemButton.measuredWidth, toolbarWidth)
            // Check if we can fit an item while reserving space for the overflowButton.
            val canFitWithOverflow =
                menuItemButtonWidth <= availableWidth - overflowButtonSize.width
            val canFitNoOverflow = isLastItem && menuItemButtonWidth <= availableWidth
            if (canFitWithOverflow || canFitNoOverflow) {
                menuItemButton.tag = menuItem
                menuItemButton.setOnClickListener(menuItemButtonOnClickListener)
                // Set tooltips for main panel items, but not overflow items (b/35726766).
                menuItemButton.tooltipText = menuItem.tooltipText
                mainPanel.addView(menuItemButton)
                val params = menuItemButton.layoutParams
                params.width = menuItemButtonWidth
                menuItemButton.layoutParams = params
                availableWidth -= menuItemButtonWidth
                remainingMenuItems.removeAt(0)
            } else {
                break
            }
            isFirstItem = false
        }
        if (!remainingMenuItems.isEmpty()) {
            // Reserve space for overflowButton.
            mainPanel.setPaddingRelative(0, 0, overflowButtonSize.width, 0)
        }
        mainPanelSize = measure(mainPanel)

        return remainingMenuItems
    }

    private fun layoutOverflowPanelItems(menuItems: List<ToolbarMenuItem>) {
        val overflowPanelAdapter = overflowPanel.adapter as ArrayAdapter<ToolbarMenuItem>
        overflowPanelAdapter.clear()
        val size = menuItems.size
        for (i in 0..<size) {
            overflowPanelAdapter.add(menuItems[i])
        }
        overflowPanel.adapter = overflowPanelAdapter
        if (openOverflowUpwards) {
            overflowPanel.y = 0f
        } else {
            overflowPanel.y = overflowButtonSize.height.toFloat()
        }
        val width = max(overflowWidth, overflowButtonSize.width)
        val height = calculateOverflowHeight(MAX_OVERFLOW_SIZE)
        overflowPanelSize = Size(width, height)
        setSize(overflowPanel, overflowPanelSize!!)
    }

    /** Resets the content container and appropriately position it's panels. */
    private fun preparePopupContent() {
        contentContainer.removeAllViews()
        // Add views in the specified order so they stack up as expected.
        // Order: overflowPanel, mainPanel, overflowButton.
        if (hasOverflow()) {
            contentContainer.addView(overflowPanel)
        }
        contentContainer.addView(mainPanel)
        if (hasOverflow()) {
            contentContainer.addView(overflowButton)
        }
        setPanelsStatesAtRestingPosition()

        // The positioning of contents in RTL is wrong when the view is first rendered.
        // Hide the view and post a runnable to recalculate positions and render the view.
        // TODO: Investigate why this happens and fix.
        if (isInRtlMode()) {
            contentContainer.alpha = 0f
            contentContainer.post(preparePopupContentRTLHelper)
        }
    }

    /** Clears out the panels and their container. Resets their calculated sizes. */
    private fun clearPanels() {
        isOverflowOpen = false
        mainPanelSize = null
        mainPanel.removeAllViews()
        overflowPanelSize = null
        val overflowPanelAdapter = overflowPanel.adapter as ArrayAdapter<ToolbarMenuItem>
        overflowPanelAdapter.clear()
        overflowPanel.adapter = overflowPanelAdapter
        contentContainer.removeAllViews()
    }

    private fun positionContentYCoordinatesIfOpeningOverflowUpwards() {
        if (openOverflowUpwards) {
            mainPanel.y = (contentContainer.height - mainPanelSize!!.height).toFloat()
            overflowButton.y = (contentContainer.height - overflowButton.height).toFloat()
            overflowPanel.y = (contentContainer.height - overflowPanelSize!!.height).toFloat()
        }
    }

    private val overflowWidth: Int
        get() {
            var overflowWidth = 0
            val count = overflowPanel.adapter.count
            for (i in 0..<count) {
                val menuItem = overflowPanel.adapter.getItem(i) as ToolbarMenuItem
                overflowWidth = max(overflowPanelViewHelper.calculateWidth(menuItem), overflowWidth)
            }
            return overflowWidth
        }

    private fun calculateOverflowHeight(maxItemSize: Int): Int {
        // Maximum of 4 items, minimum of 2 if the overflow has to scroll.
        val actualSize =
            min(MAX_OVERFLOW_SIZE, min(max(MIN_OVERFLOW_SIZE, maxItemSize), overflowPanel.count))
        var extension = 0
        if (actualSize < overflowPanel.count) {
            // The overflow will require scrolling to get to all the items.
            // Extend the height so that part of the hidden items is displayed.
            extension = (lineHeight * 0.5f).toInt()
        }
        return (actualSize * lineHeight + overflowButtonSize.height + extension)
    }

    private val animationDuration: Int
        /**
         * NOTE: Use only in android.view.animation.* animations. Do not use in android.animation.*
         * animations. See comment about this in the code.
         */
        get() {
            if (transitionDurationScale < 150) {
                // For smaller transition, decrease the time.
                return 200
            } else if (transitionDurationScale > 300) {
                // For bigger transition, increase the time.
                return 300
            }

            // Scale the animation duration with getDurationScale(). This allows
            // android.view.animation.* animations to scale just like android.animation.* animations
            // when  animator duration scale is adjusted in "Developer Options".
            // For this reason, do not use this method for android.animation.* animations.
            return (250 * ValueAnimator.getDurationScale()).toInt()
        }

    private fun maybeComputeTransitionDurationScale() {
        if (mainPanelSize != null && overflowPanelSize != null) {
            val w = mainPanelSize!!.width - overflowPanelSize!!.width
            val h = overflowPanelSize!!.height - mainPanelSize!!.height
            transitionDurationScale =
                (sqrt((w * w + h * h).toDouble()) /
                        contentContainer.context.resources.displayMetrics.density)
                    .toInt()
        }
    }

    private fun createMainPanel(): ViewGroup {
        return object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                var widthMeasureSpec = widthMeasureSpec
                if (isOverflowAnimating()) {
                    // Update widthMeasureSpec to make sure that this view is not clipped
                    // as we offset its coordinates with respect to its parent.
                    widthMeasureSpec =
                        MeasureSpec.makeMeasureSpec(mainPanelSize!!.width, MeasureSpec.EXACTLY)
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                // Intercept the touch event while the overflow is animating.
                return isOverflowAnimating()
            }
        }
    }

    private fun createOverflowButton(): ImageButton {
        val overflowButton =
            LayoutInflater.from(context).inflate(R.layout.floating_popup_overflow_button, null)
                as ImageButton
        overflowButton.setImageDrawable(overflow)
        overflowButton.setOnClickListener { _: View ->
            if (isShowing) {
                preparePopupContent()
                val widgetInfo = createWidgetInfo()
                surfaceControlViewHost!!.relayout(popupWidth, popupHeight)
                callbackWrapper.onWidgetUpdated(widgetInfo)
            }
            if (isOverflowOpen) {
                overflowButton.setImageDrawable(toOverflow)
                toOverflow.start()
                closeOverflow()
            } else {
                overflowButton.setImageDrawable(toArrow)
                toArrow.start()
                openOverflow()
            }
        }
        return overflowButton
    }

    private fun createOverflowPanel(): OverflowPanel {
        val overflowPanel = OverflowPanel(this)
        overflowPanel.layoutParams =
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        overflowPanel.divider = null
        overflowPanel.dividerHeight = 0

        val adapter: ArrayAdapter<*> =
            object : ArrayAdapter<ToolbarMenuItem>(context, 0) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    return overflowPanelViewHelper.getView(
                        getItem(position)!!,
                        overflowPanelSize!!.width,
                        convertView,
                    )
                }
            }
        overflowPanel.adapter = adapter
        overflowPanel.setOnItemClickListener { _, _, position: Int, _ ->
            val menuItem = overflowPanel.adapter.getItem(position) as ToolbarMenuItem
            dispatchMenuItemClick(menuItem)
        }
        return overflowPanel
    }

    private fun isOverflowAnimating(): Boolean {
        val overflowOpening =
            openOverflowAnimation.hasStarted() && !openOverflowAnimation.hasEnded()
        val overflowClosing =
            closeOverflowAnimation.hasStarted() && !closeOverflowAnimation.hasEnded()
        return overflowOpening || overflowClosing
    }

    private fun createOverflowAnimationListener(): Animation.AnimationListener {
        return object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {
                // Disable the overflow button while it's animating.
                // It will be re-enabled when the animation stops.
                overflowButton.isEnabled = false
                // Ensure both panels have visibility turned on when the overflow animation
                // starts.
                mainPanel.visibility = View.VISIBLE
                overflowPanel.visibility = View.VISIBLE
            }

            override fun onAnimationEnd(animation: Animation) {
                // Posting this because it seems like this is called before the animation
                // actually ends.
                contentContainer.post {
                    setPanelsStatesAtRestingPosition()
                    if (isShowing) {
                        callbackWrapper.onWidgetUpdated(createWidgetInfo())
                    }
                }
            }

            override fun onAnimationRepeat(animation: Animation) {}
        }
    }

    /** A custom ListView for the overflow panel. */
    private inner class OverflowPanel(private val mPopup: RemoteSelectionToolbar) :
        ListView(Objects.requireNonNull(mPopup).context) {
        init {
            scrollBarDefaultDelayBeforeFade = ViewConfiguration.getScrollDefaultDelay() * 3
            scrollIndicators = SCROLL_INDICATOR_TOP or SCROLL_INDICATOR_BOTTOM
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // Update heightMeasureSpec to make sure that this view is not clipped
            // as we offset it's coordinates with respect to its parent.
            val height = (mPopup.overflowPanelSize!!.height - mPopup.overflowButtonSize.height)
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (isOverflowAnimating()) {
                // Eat the touch event.
                return true
            }
            return super.dispatchTouchEvent(ev)
        }

        // Overrides to change visibility
        public override fun awakenScrollBars(): Boolean {
            return super.awakenScrollBars()
        }
    }

    /** A custom interpolator used for various floating toolbar animations. */
    private class LogAccelerateInterpolator : Interpolator {
        override fun getInterpolation(t: Float): Float {
            return 1 - computeLog(1 - t, BASE) * LOGS_SCALE
        }

        companion object {
            private const val BASE = 100
            private val LOGS_SCALE: Float = 1f / computeLog(1f, BASE)

            private fun computeLog(t: Float, base: Int): Float {
                return (1 - base.toDouble().pow(-t.toDouble())).toFloat()
            }
        }
    }

    /** A helper for generating views for the overflow panel. */
    private class OverflowPanelViewHelper(context: Context, private val mIconTextSpacing: Int) {
        private val mContext = Objects.requireNonNull(context)
        private val mSidePadding: Int =
            context.resources.getDimensionPixelSize(R.dimen.floating_toolbar_overflow_side_padding)
        private val mCalculator: View = createMenuButton(null)

        fun getView(menuItem: ToolbarMenuItem, minimumWidth: Int, convertView: View?): View {
            var convertView = convertView
            if (convertView != null) {
                updateMenuItemButton(
                    convertView,
                    menuItem,
                    mIconTextSpacing,
                    shouldShowIcon(menuItem),
                )
            } else {
                convertView = createMenuButton(menuItem)
            }
            convertView.minimumWidth = minimumWidth
            return convertView
        }

        fun calculateWidth(menuItem: ToolbarMenuItem): Int {
            updateMenuItemButton(mCalculator, menuItem, mIconTextSpacing, shouldShowIcon(menuItem))
            mCalculator.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
            return mCalculator.measuredWidth
        }

        fun createMenuButton(menuItem: ToolbarMenuItem?): View {
            val button: View =
                createMenuItemButton(mContext, menuItem, mIconTextSpacing, shouldShowIcon(menuItem))
            button.setPadding(mSidePadding, 0, mSidePadding, 0)
            return button
        }

        fun shouldShowIcon(menuItem: ToolbarMenuItem?): Boolean {
            if (menuItem != null) {
                return menuItem.groupId == android.R.id.textAssist
            }
            return false
        }
    }

    /** Dumps information about this class. */
    fun dump(prefix: String, pw: PrintWriter) {
        pw.print(prefix)
        pw.print("state: ")
        pw.println(
            when (state) {
                TOOLBAR_STATE_SHOWN -> "SHOWN"
                TOOLBAR_STATE_HIDDEN -> "HIDDEN"
                TOOLBAR_STATE_DISMISSED -> "DISMISSED"
                else -> "UNKNOWN"
            }
        )
        pw.print(prefix)
        pw.print("popup width: ")
        pw.println(popupWidth)
        pw.print(prefix)
        pw.print("popup height: ")
        pw.println(popupHeight)
        pw.print(prefix)
        pw.print("relative coords: ")
        pw.println(relativeCoordsForToolbar)
        pw.print(prefix)
        pw.print("main panel size: ")
        pw.println(mainPanelSize)
        val hasOverflow = hasOverflow()
        pw.print(prefix)
        pw.print("has overflow: ")
        pw.println(hasOverflow)
        if (hasOverflow) {
            pw.print(prefix)
            pw.print("overflow open: ")
            pw.println(isOverflowOpen)
            pw.print(prefix)
            pw.print("overflow size: ")
            pw.println(overflowPanelSize)
        }
        if (menuItems != null) {
            val menuItemSize = menuItems!!.size
            pw.print(prefix)
            pw.print("number menu items: ")
            pw.println(menuItemSize)
            for (i in 0..<menuItemSize) {
                pw.print(prefix)
                pw.print("#")
                pw.println(i)
                pw.print("$prefix  ")
                pw.println(menuItems!![i])
            }
        }
    }

    companion object {
        private const val TAG = "RemoteSelectionToolbar"

        /* Minimum and maximum number of items allowed in the overflow. */
        private const val MIN_OVERFLOW_SIZE = 2
        private const val MAX_OVERFLOW_SIZE = 4

        private const val TOOLBAR_STATE_SHOWN = 1
        private const val TOOLBAR_STATE_HIDDEN = 2
        private const val TOOLBAR_STATE_DISMISSED = 3

        private fun measure(view: View): Size {
            Preconditions.checkState(view.parent == null)
            view.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
            return Size(view.measuredWidth, view.measuredHeight)
        }

        private fun setSize(view: View, width: Int, height: Int) {
            view.apply {
                minimumWidth = width
                minimumHeight = height
                layoutParams = layoutParams ?: ViewGroup.LayoutParams(0, 0)
                layoutParams.width = width
                layoutParams.height = height
            }
        }

        private fun setSize(view: View, size: Size) {
            setSize(view, size.width, size.height)
        }

        private fun setWidth(view: View, width: Int) {
            val params = view.layoutParams
            setSize(view, width, params.height)
        }

        private fun setHeight(view: View, height: Int) {
            val params = view.layoutParams
            setSize(view, params.width, height)
        }

        private fun transformMenuItems(
            context: Context,
            menuItems: MutableList<ToolbarMenuItem>,
        ): MutableList<ToolbarMenuItem> {
            val transformedMenuItems = ArrayList<ToolbarMenuItem>(menuItems.size)
            for (i in menuItems.indices) {
                val menuItem = menuItems[i]

                transformedMenuItems.add(
                    when (SecurePasteToolbar.resolvePasteActionId(context, menuItem)) {
                        android.R.id.paste -> {
                            val newMenuItem = ToolbarMenuItem()

                            newMenuItem.groupId = menuItem.groupId
                            newMenuItem.priority = menuItem.priority
                            newMenuItem.itemIndex = menuItem.itemIndex

                            newMenuItem.itemId = android.R.id.paste
                            newMenuItem.title = context.getString(R.string.paste)
                            newMenuItem.contentDescription = context.getString(R.string.paste)

                            newMenuItem
                        }

                        android.R.id.pasteAsPlainText -> {
                            val newMenuItem = ToolbarMenuItem()

                            newMenuItem.groupId = menuItem.groupId
                            newMenuItem.priority = menuItem.priority
                            newMenuItem.itemIndex = menuItem.itemIndex

                            newMenuItem.itemId = android.R.id.pasteAsPlainText
                            newMenuItem.title = context.getString(R.string.paste_as_plain_text)
                            newMenuItem.contentDescription =
                                context.getString(R.string.paste_as_plain_text)

                            newMenuItem
                        }

                        else -> menuItem
                    }
                )
            }

            return transformedMenuItems
        }

        /** Creates and returns a menu button for the specified menu item. */
        private fun createMenuItemButton(
            context: Context,
            menuItem: ToolbarMenuItem?,
            iconTextSpacing: Int,
            showIcon: Boolean,
        ): View {
            val menuItemButton =
                LayoutInflater.from(context).inflate(R.layout.floating_popup_menu_button, null)
            menuItem?.let { updateMenuItemButton(menuItemButton, it, iconTextSpacing, showIcon) }
            return menuItemButton
        }

        /** Updates the specified menu item button with the specified menu item data. */
        private fun updateMenuItemButton(
            menuItemButton: View,
            menuItem: ToolbarMenuItem,
            iconTextSpacing: Int,
            showIcon: Boolean,
        ) {
            val buttonText =
                menuItemButton.findViewById<TextView>(R.id.floating_toolbar_menu_item_text)
            buttonText.ellipsize = null
            if (TextUtils.isEmpty(menuItem.title)) {
                buttonText.visibility = View.GONE
            } else {
                buttonText.visibility = View.VISIBLE
                buttonText.text = menuItem.title
            }
            val buttonIcon =
                menuItemButton.findViewById<ImageView>(R.id.floating_toolbar_menu_item_image)
            if (menuItem.icon == null || !showIcon) {
                buttonIcon.visibility = View.GONE
                buttonText.setPaddingRelative(0, 0, 0, 0)
            } else {
                buttonIcon.visibility = View.VISIBLE
                buttonIcon.setImageDrawable(menuItem.icon.loadDrawable(menuItemButton.context))
                buttonText.setPaddingRelative(iconTextSpacing, 0, 0, 0)
            }
            val contentDescription = menuItem.contentDescription
            if (TextUtils.isEmpty(contentDescription)) {
                menuItemButton.contentDescription = menuItem.title
            } else {
                menuItemButton.contentDescription = contentDescription
            }
        }

        private fun createContentContainer(context: Context): ViewGroup {
            val contentContainer =
                LayoutInflater.from(context).inflate(R.layout.floating_popup_container, null)
                    as ViewGroup
            contentContainer.layoutParams =
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            contentContainer.tag = FloatingToolbar.FLOATING_TOOLBAR_TAG
            contentContainer.clipToOutline = true
            return contentContainer
        }

        /**
         * Creates an "appear" animation for the specified view.
         *
         * @param view The view to animate
         */
        private fun createEnterAnimation(view: View): AnimatorSet {
            val animation = AnimatorSet()
            animation.playTogether(
                ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f).setDuration(150)
            )
            return animation
        }

        /**
         * Creates a "disappear" animation for the specified view.
         *
         * @param view The view to animate
         * @param startDelay The start delay of the animation
         * @param listener The animation listener
         */
        private fun createExitAnimation(
            view: View,
            startDelay: Int,
            listener: Animator.AnimatorListener?,
        ): AnimatorSet {
            val animation = AnimatorSet()
            animation.playTogether(
                ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f).setDuration(100)
            )
            animation.startDelay = startDelay.toLong()
            if (listener != null) {
                animation.addListener(listener)
            }
            return animation
        }

        /** Returns a re-themed context with controlled look and feel for views. */
        private fun wrapContext(originalContext: Context, showInfo: ShowInfo): Context {
            val themeId =
                if (showInfo.isLightTheme) R.style.Theme_DeviceDefault_Light
                else R.style.Theme_DeviceDefault
            return ContextThemeWrapper(originalContext, themeId)
                .createConfigurationContext(showInfo.configuration)
        }

        private fun debugLog(message: String) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, message)
            }
        }
    }
}
