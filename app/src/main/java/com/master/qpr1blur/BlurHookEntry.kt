package com.master.qpr1blur

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.PopupWindow
import android.widget.SeekBar
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections
import java.util.WeakHashMap

class BlurHookEntry : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "[QPR1BlurPort]"

        // 目标高斯模糊半径（像素）：自动同步系统原生值，兜底 110px
        @Volatile
        private var targetBlurRadiusPx = 110

        // 卡片与音量大面板底色不透明度（0~255）：推荐 165 ~ 185
        private const val PANEL_BG_ALPHA = 170

        // 渐进模糊动画时长（毫秒）
        private const val BLUR_ANIM_DURATION_MS = 220L

        private const val TAG_BLUR_ATTACHED = 0x51505231

        // 追踪目标弹窗内的 ComposeView，供画布拦截器识别底板
        private val trackedComposeViews: MutableSet<View> =
            Collections.newSetFromMap(WeakHashMap())

        private val activeComposeWidth = ThreadLocal<Float>()
        private val activeComposeHeight = ThreadLocal<Float>()
        private val savedPaintAlpha = ThreadLocal<Int>()
        private val tempBounds = ThreadLocal<RectF>()
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return

        XposedBridge.log("$TAG 已注入 SystemUI，启用全系原生 BackgroundBlurDrawable + Compose 穿透引擎...")

        sniffNativeBlurRadius(lpparam.classLoader)
        hookComposeSurfaceTransparency()
        hookDialogs()
        hookPopupWindows()
        hookOverlayWindows(lpparam.classLoader)
    }

    /**
     * 专治 Jetpack Compose 展开音量大面板：仅在目标 ComposeView 绘制底板大圆角时降低画笔不透明度
     */
    private fun hookComposeSurfaceTransparency() {
        XposedBridge.hookAllMethods(ViewGroup::class.java, "dispatchDraw", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (trackedComposeViews.contains(view)) {
                    activeComposeWidth.set(view.width.toFloat())
                    activeComposeHeight.set(view.height.toFloat())
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (activeComposeWidth.get() != null) {
                    activeComposeWidth.remove()
                    activeComposeHeight.remove()
                }
            }
        })

        val baseCanvasClass = XposedHelpers.findClassIfExists(
            "android.graphics.BaseRecordingCanvas",
            null
        ) ?: return

        val canvasDrawHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val w = activeComposeWidth.get() ?: return
                val h = activeComposeHeight.get() ?: return
                if (w <= 0f || h <= 0f) return

                val paint = param.args.lastOrNull() as? Paint ?: return
                if (paint.style == Paint.Style.STROKE || paint.alpha <= PANEL_BG_ALPHA) return

                val isLargeBg = when (param.method.name) {
                    "drawPath" -> {
                        val path = param.args.firstOrNull() as? Path ?: return
                        val rect = tempBounds.get() ?: RectF().also { tempBounds.set(it) }
                        path.computeBounds(rect, true)
                        rect.width() >= w * 0.85f && rect.height() >= h * 0.55f
                    }
                    "drawRoundRect", "drawRect" -> {
                        if (param.args.size >= 5 && param.args[0] is Float) {
                            val left = param.args[0] as Float
                            val top = param.args[1] as Float
                            val right = param.args[2] as Float
                            val bottom = param.args[3] as Float
                            (right - left) >= w * 0.85f && (bottom - top) >= h * 0.55f
                        } else if (param.args.firstOrNull() is RectF) {
                            val r = param.args[0] as RectF
                            r.width() >= w * 0.85f && r.height() >= h * 0.55f
                        } else {
                            false
                        }
                    }
                    else -> false
                }

                if (isLargeBg) {
                    savedPaintAlpha.set(paint.alpha)
                    paint.alpha = PANEL_BG_ALPHA
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val origAlpha = savedPaintAlpha.get() ?: return
                savedPaintAlpha.remove()
                val paint = param.args.lastOrNull() as? Paint ?: return
                paint.alpha = origAlpha
            }
        }

        XposedBridge.hookAllMethods(baseCanvasClass, "drawPath", canvasDrawHook)
        XposedBridge.hookAllMethods(baseCanvasClass, "drawRoundRect", canvasDrawHook)
        XposedBridge.hookAllMethods(baseCanvasClass, "drawRect", canvasDrawHook)
    }

    private fun sniffNativeBlurRadius(classLoader: ClassLoader) {
        val blurUtilsClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.BlurUtils",
            classLoader
        ) ?: return

        XposedBridge.hookAllMethods(blurUtilsClass, "blurRadiusOfRatio", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val ratio = param.args.firstOrNull() as? Float ?: return
                val result = param.result as? Int ?: return
                if (ratio >= 0.95f && result in 40..250 && result != targetBlurRadiusPx) {
                    targetBlurRadiusPx = result
                    XposedBridge.log("$TAG 已同步系统原生模糊半径: ${targetBlurRadiusPx}px")
                }
            }
        })
    }

    private fun hookDialogs() {
        XposedBridge.hookAllMethods(Dialog::class.java, "onStart", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val dialog = param.thisObject as? Dialog ?: return
                val className = dialog.javaClass.name
                val window = dialog.window ?: return
                if (!isTargetComponent(className)) return

                prepareWindowFormat(window)
                val decor = window.decorView
                attachBlurWhenReady(decor, isDialog = true, label = className)
            }
        })
    }

    private fun hookPopupWindows() {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val popup = param.thisObject as? PopupWindow ?: return
                val content = popup.contentView ?: return
                attachBlurWhenReady(content, isDialog = false, label = "PopupWindow")
            }
        }
        XposedBridge.hookAllMethods(PopupWindow::class.java, "showAtLocation", hook)
        XposedBridge.hookAllMethods(PopupWindow::class.java, "showAsDropDown", hook)
    }

    private fun hookOverlayWindows(classLoader: ClassLoader) {
        val wmgClass = XposedHelpers.findClassIfExists(
            "android.view.WindowManagerGlobal",
            classLoader
        ) ?: return

        XposedBridge.hookAllMethods(wmgClass, "addView", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args.size < 2) return
                val view = param.args[0] as? View ?: return
                val params = param.args[1] as? WindowManager.LayoutParams ?: return
                val title = params.title?.toString() ?: ""

                val isTargetWindow = params.type == 2020 ||
                        params.type == 2017 ||
                        params.type == 2008 ||
                        isTargetComponent(title)

                if (isTargetWindow && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    params.format = PixelFormat.TRANSLUCENT
                    params.flags = params.flags and
                            WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv() and
                            WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
                    params.blurBehindRadius = 0

                    attachBlurWhenReady(view, isDialog = false, label = title)
                }
            }
        })
    }

    private fun prepareWindowFormat(window: Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setFormat(PixelFormat.TRANSLUCENT)
            window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = 0 }
        }
    }

    private fun attachBlurWhenReady(root: View, isDialog: Boolean, label: String) {
        val applyTask = Runnable {
            try {
                val viewRootImpl = XposedHelpers.callMethod(root, "getViewRootImpl")
                if (viewRootImpl == null) {
                    XposedBridge.log("$TAG 未获取到 ViewRootImpl，跳过: $label")
                    return@Runnable
                }

                // 1. 扫描并标记内部所有 ComposeView（展开音量大面板）
                val composeViews = mutableListOf<View>()
                findComposeViews(root, composeViews, 0)
                for (cv in composeViews) {
                    trackedComposeViews.add(cv)
                    cv.invalidate()
                }

                // 2. 找出带有圆角背景的传统卡片容器
                val targetViews = mutableListOf<View>()
                findBackgroundContainers(root, targetViews, 0)

                // 如果找不到传统背景但存在 ComposeView，直接将模糊层贴到 ComposeView 上
                if (targetViews.isEmpty() && composeViews.isNotEmpty()) {
                    targetViews.addAll(composeViews)
                } else if (targetViews.isEmpty()) {
                    targetViews.add(root)
                }

                val isBottomSheet = label.contains("Volume", ignoreCase = true) ||
                        label.contains("Sheet", ignoreCase = true) ||
                        composeViews.isNotEmpty()
                val defaultCornerPx = dpToPx(root.context, if (isDialog) 28f else 26f)

                for (card in targetViews) {
                    applyHardwareBlurDrawable(card, viewRootImpl, defaultCornerPx, isBottomSheet && isDialog)
                }
                XposedBridge.log(
                    "$TAG 原生模糊挂载成功 (容器=${targetViews.size}, Compose=${composeViews.size}, ${targetBlurRadiusPx}px): $label"
                )
            } catch (t: Throwable) {
                XposedBridge.log("$TAG 挂载模糊异常 ($label): ${t.message}")
            }
        }

        if (root.isAttachedToWindow) {
            root.post(applyTask)
        } else {
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    v.post(applyTask)
                }
                override fun onViewDetachedFromWindow(v: View) {}
            })
        }
    }

    private fun applyHardwareBlurDrawable(
        view: View,
        viewRootImpl: Any,
        fallbackCornerPx: Float,
        bottomSheetCorners: Boolean
    ) {
        if (view.getTag(TAG_BLUR_ATTACHED) == true) return
        view.setTag(TAG_BLUR_ATTACHED, true)

        val blurDrawable = XposedHelpers.callMethod(
            viewRootImpl,
            "createBackgroundBlurDrawable"
        ) as? Drawable ?: return

        val cornerPx = extractCornerRadius(view.background) ?: fallbackCornerPx
        try {
            if (bottomSheetCorners) {
                // 底部抽屉仅顶部两角圆角，底部贴边为直角
                XposedHelpers.callMethod(
                    blurDrawable,
                    "setCornerRadius",
                    cornerPx, cornerPx, 0f, 0f
                )
            } else {
                XposedHelpers.callMethod(blurDrawable, "setCornerRadius", cornerPx)
            }
        } catch (_: Throwable) {
            XposedHelpers.callMethod(blurDrawable, "setCornerRadius", cornerPx)
        }

        XposedHelpers.callMethod(blurDrawable, "setColor", Color.TRANSPARENT)
        XposedHelpers.callMethod(blurDrawable, "setBlurRadius", 0)

        val originalBg = view.background?.mutate()
        if (originalBg != null) {
            applyAlphaToDrawable(originalBg, PANEL_BG_ALPHA)
            view.backgroundTintList?.let { tint ->
                val c = tint.defaultColor
                view.backgroundTintList = ColorStateList.valueOf(
                    Color.argb(PANEL_BG_ALPHA, Color.red(c), Color.green(c), Color.blue(c))
                )
            }

            val layer = LayerDrawable(arrayOf(blurDrawable, originalBg))
            // 若原背景带有 InsetDrawable 边距，同步给模糊层防止溢出卡片外框
            val pad = Rect()
            if (originalBg.getPadding(pad) && (pad.left > 0 || pad.top > 0 || pad.right > 0 || pad.bottom > 0)) {
                layer.setLayerInset(0, pad.left, pad.top, pad.right, pad.bottom)
            }
            view.background = layer
        } else {
            view.background = blurDrawable
        }

        // 启动丝滑渐进模糊动画（0px -> targetBlurRadiusPx）
        ValueAnimator.ofInt(0, targetBlurRadiusPx).apply {
            duration = BLUR_ANIM_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                try {
                    val currentRadius = anim.animatedValue as Int
                    XposedHelpers.callMethod(blurDrawable, "setBlurRadius", currentRadius)
                    view.invalidate()
                } catch (_: Throwable) {
                }
            }
            start()
        }
    }

    private fun findComposeViews(view: View, out: MutableList<View>, depth: Int) {
        if (depth > 10) return
        val name = view.javaClass.name
        if (name.contains("AndroidComposeView", ignoreCase = true) ||
            name.contains("ComposeView", ignoreCase = true)
        ) {
            out.add(view)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findComposeViews(view.getChildAt(i), out, depth + 1)
            }
        }
    }

    private fun findBackgroundContainers(view: View, out: MutableList<View>, depth: Int) {
        if (depth > 6 || view is SeekBar) return

        val bg = view.background
        if (bg != null && bg !is ColorDrawable) {
            out.add(view)
            if (depth >= 4) return
        } else if (bg is ColorDrawable && Color.alpha(bg.color) > 0) {
            out.add(view)
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findBackgroundContainers(view.getChildAt(i), out, depth + 1)
            }
        }
    }

    private fun extractCornerRadius(drawable: Drawable?): Float? {
        if (drawable == null) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && drawable is DrawableWrapper) {
            return extractCornerRadius(drawable.drawable)
        }
        return when (drawable) {
            is GradientDrawable -> if (drawable.cornerRadius > 0f) drawable.cornerRadius else null
            is LayerDrawable -> {
                for (i in 0 until drawable.numberOfLayers) {
                    val r = extractCornerRadius(drawable.getDrawable(i))
                    if (r != null) return r
                }
                null
            }
            else -> null
        }
    }

    private fun applyAlphaToDrawable(drawable: Drawable, alpha: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && drawable is DrawableWrapper) {
            drawable.drawable?.let { applyAlphaToDrawable(it, alpha) }
            drawable.alpha = alpha
            return
        }
        when (drawable) {
            is RippleDrawable -> {
                for (i in 0 until drawable.numberOfLayers) {
                    applyAlphaToDrawable(drawable.getDrawable(i), alpha)
                }
            }
            is LayerDrawable -> {
                for (i in 0 until drawable.numberOfLayers) {
                    applyAlphaToDrawable(drawable.getDrawable(i), alpha)
                }
            }
            is GradientDrawable -> {
                drawable.alpha = alpha
                drawable.color?.let { csl ->
                    val c = csl.defaultColor
                    drawable.color = ColorStateList.valueOf(
                        Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
                    )
                }
            }
            is ColorDrawable -> {
                val c = drawable.color
                drawable.color = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
            }
            else -> {
                drawable.alpha = alpha
                // 兼容 MaterialShapeDrawable 的 fillColor
                if (drawable.javaClass.name.contains("MaterialShapeDrawable")) {
                    try {
                        val fillColor = XposedHelpers.callMethod(drawable, "getFillColor") as? ColorStateList
                        if (fillColor != null) {
                            val c = fillColor.defaultColor
                            XposedHelpers.callMethod(
                                drawable,
                                "setFillColor",
                                ColorStateList.valueOf(Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c)))
                            )
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private fun isTargetComponent(name: String): Boolean {
        return name.contains("Volume", ignoreCase = true) ||
                name.contains("SystemUIDialog", ignoreCase = true) ||
                name.contains("BottomSheet", ignoreCase = true) ||
                name.contains("Sheet", ignoreCase = true) ||
                name.contains("DialogWrapper", ignoreCase = true) ||
                name.contains("QS", ignoreCase = true) ||
                name.contains("Detail", ignoreCase = true) ||
                name.contains("Menu", ignoreCase = true) ||
                name.contains("Media", ignoreCase = true) ||
                name.contains("Output", ignoreCase = true) ||
                name.contains("Popup", ignoreCase = true)
    }

    private fun dpToPx(context: Context, dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }
}
