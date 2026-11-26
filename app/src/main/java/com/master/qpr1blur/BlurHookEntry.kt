package com.master.qpr1blur

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
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

        // 动态同步的系统原生模糊半径（初始兜底 150px，注入后自动对齐通知栏原生最大值）
        @Volatile
        private var nativeSystemBlurRadiusPx = 150

        // 面板与静音按钮外圈的底色不透明度（0~255）：约 72% 浓度，兼具动态取色饱和度与毛玻璃通透感
        private const val PANEL_BG_ALPHA = 185

        // 弱引用追踪已纳入毛玻璃管理的视图树，从源头杜绝后续重绘恢复实心底色
        private val trackedViews: MutableSet<View> =
            Collections.newSetFromMap(WeakHashMap())
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return

        XposedBridge.log("$TAG 已注入 SystemUI，启用首帧零延迟原生级毛玻璃引擎...")

        sniffNativeBlurRadius(lpparam.classLoader)
        hookBackgroundSetters()
        hookDialogs()
        hookPopupWindows()
        hookOverlayWindows(lpparam.classLoader)
        hookEmbeddedDropoutViews()
    }

    /**
     * 核心防闪烁：在 View 设置背景和 Tint 的源头直接拦截，杜绝首帧出现实心底色
     */
    private fun hookBackgroundSetters() {
        XposedBridge.hookAllMethods(View::class.java, "setBackgroundDrawable", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (!isTrackedOrInsideTarget(view)) return
                val drawable = param.args.firstOrNull() as? Drawable ?: return
                val mutated = drawable.mutate()
                applyAlphaToDrawable(mutated, PANEL_BG_ALPHA)
                param.args[0] = mutated
            }
        })

        XposedBridge.hookAllMethods(View::class.java, "setBackgroundTintList", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (!isTrackedOrInsideTarget(view)) return
                val tintList = param.args.firstOrNull() as? ColorStateList ?: return
                val defaultColor = tintList.defaultColor
                if (Color.alpha(defaultColor) > PANEL_BG_ALPHA) {
                    param.args[0] = ColorStateList.valueOf(
                        Color.argb(
                            PANEL_BG_ALPHA,
                            Color.red(defaultColor),
                            Color.green(defaultColor),
                            Color.blue(defaultColor)
                        )
                    )
                }
            }
        })
    }

    private fun isTrackedOrInsideTarget(view: View): Boolean {
        if (view is SeekBar) return false
        if (trackedViews.contains(view)) return true
        var p = view.parent
        var depth = 0
        while (p is View && depth < 8) {
            if (trackedViews.contains(p)) {
                trackedViews.add(view)
                return true
            }
            p = p.parent
            depth++
        }
        return false
    }

    /**
     * 实时嗅探系统通知栏 BlurUtils 的最大模糊像素值，保证与系统原生模糊颗粒度 1:1 一致
     */
    private fun sniffNativeBlurRadius(classLoader: ClassLoader) {
        val blurUtilsClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.BlurUtils",
            classLoader
        )
        if (blurUtilsClass != null) {
            XposedBridge.hookAllMethods(blurUtilsClass, "blurRadiusOfRatio", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val ratio = param.args.firstOrNull() as? Float ?: return
                    val result = param.result as? Int ?: return
                    if (ratio >= 0.95f && result > 0 && result != nativeSystemBlurRadiusPx) {
                        nativeSystemBlurRadiusPx = result
                        XposedBridge.log("$TAG 已同步系统原生满载模糊半径: ${nativeSystemBlurRadiusPx}px")
                    }
                }
            })

            XposedBridge.hookAllMethods(blurUtilsClass, "applyBlur", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val radius = param.args.getOrNull(1) as? Int ?: return
                    if (radius > 60 && radius > nativeSystemBlurRadiusPx) {
                        nativeSystemBlurRadiusPx = radius
                        XposedBridge.log("$TAG 已捕获系统最大模糊半径: ${nativeSystemBlurRadiusPx}px")
                    }
                }
            })
        }
    }

    private fun updateRadiusFromContext(context: Context?) {
        if (context == null) return
        try {
            val res = context.resources
            val resNames = listOf(
                "max_window_blur_radius",
                "config_max_window_blur_radius",
                "notification_shade_blur_radius"
            )
            for (name in resNames) {
                val id = res.getIdentifier(name, "dimen", "com.android.systemui")
                    .takeIf { it != 0 }
                    ?: res.getIdentifier(name, "dimen", "android")
                if (id != 0) {
                    val px = res.getDimensionPixelSize(id)
                    if (px > 0) {
                        nativeSystemBlurRadiusPx = px
                        break
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun hookDialogs() {
        XposedBridge.hookAllMethods(Dialog::class.java, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                prepareDialogBlur(param.thisObject as? Dialog)
            }
        })
        XposedBridge.hookAllMethods(Dialog::class.java, "onStart", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                prepareDialogBlur(param.thisObject as? Dialog)
            }
        })
    }

    private fun prepareDialogBlur(dialog: Dialog?) {
        if (dialog == null) return
        try {
            val className = dialog.javaClass.name
            val window = dialog.window ?: return
            updateRadiusFromContext(dialog.context)

            if (isTargetComponent(className)) {
                applyPanelOnlyBlur(window, nativeSystemBlurRadiusPx)
                val decor = window.decorView
                markTreeTracked(decor, 0)
                makeContainerTranslucent(decor, PANEL_BG_ALPHA, 0)
                XposedBridge.log("$TAG 已为 Dialog 注入零延迟模糊 (${nativeSystemBlurRadiusPx}px): $className")
            }
        } catch (_: Throwable) {
        }
    }

    private fun hookPopupWindows() {
        XposedBridge.hookAllMethods(PopupWindow::class.java, "showAtLocation", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                handlePopupWindow(param.thisObject as? PopupWindow)
            }
        })
        XposedBridge.hookAllMethods(PopupWindow::class.java, "showAsDropDown", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                handlePopupWindow(param.thisObject as? PopupWindow)
            }
        })
    }

    private fun handlePopupWindow(popup: PopupWindow?) {
        if (popup == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val contentView = popup.contentView ?: return
            updateRadiusFromContext(contentView.context)
            markTreeTracked(contentView, 0)
            makeContainerTranslucent(contentView, PANEL_BG_ALPHA, 0)

            val rootView = contentView.rootView ?: return
            val viewRootImpl = XposedHelpers.callMethod(rootView, "getViewRootImpl")
            if (viewRootImpl != null) {
                XposedHelpers.callMethod(viewRootImpl, "setBackgroundBlurRadius", nativeSystemBlurRadiusPx)
            }
        } catch (_: Throwable) {
        }
    }

    private fun hookOverlayWindows(classLoader: ClassLoader) {
        val wmgClass = XposedHelpers.findClassIfExists(
            "android.view.WindowManagerGlobal",
            classLoader
        ) ?: return

        XposedBridge.hookAllMethods(wmgClass, "addView", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    if (param.args.size < 2) return
                    val view = param.args[0] as? View ?: return
                    val params = param.args[1] as? WindowManager.LayoutParams ?: return

                    updateRadiusFromContext(view.context)

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

                        // 1. 立即同步标记并剥离实心底色
                        markTreeTracked(view, 0)
                        makeContainerTranslucent(view, PANEL_BG_ALPHA, 0)

                        // 2. 在首帧光栅化提交（onPreDraw）前同步写入模糊半径，消除首帧跳变
                        view.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                            override fun onPreDraw(): Boolean {
                                try {
                                    val viewRootImpl = XposedHelpers.callMethod(view, "getViewRootImpl")
                                    if (viewRootImpl != null) {
                                        XposedHelpers.callMethod(
                                            viewRootImpl,
                                            "setBackgroundBlurRadius",
                                            nativeSystemBlurRadiusPx
                                        )
                                    }
                                    makeContainerTranslucent(view, PANEL_BG_ALPHA, 0)
                                } catch (_: Throwable) {
                                } finally {
                                    if (view.viewTreeObserver.isAlive) {
                                        view.viewTreeObserver.removeOnPreDrawListener(this)
                                    }
                                }
                                return true
                            }
                        })
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG WindowManagerGlobal 异常: ${t.message}")
                }
            }
        })
    }

    private fun hookEmbeddedDropoutViews() {
        XposedBridge.hookAllMethods(ViewGroup::class.java, "addView", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val parent = param.thisObject as? View ?: return
                    val child = param.args.firstOrNull() as? View ?: return
                    val name = child.javaClass.name

                    if (trackedViews.contains(parent) ||
                        name.contains("QSDetail", ignoreCase = true) ||
                        name.contains("Dropdown", ignoreCase = true) ||
                        name.contains("PopupMenu", ignoreCase = true)
                    ) {
                        markTreeTracked(child, 0)
                        makeContainerTranslucent(child, PANEL_BG_ALPHA, 0)
                    }
                } catch (_: Throwable) {
                }
            }
        })
    }

    private fun markTreeTracked(view: View, depth: Int) {
        if (depth > 8 || view is SeekBar) return
        trackedViews.add(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                markTreeTracked(view.getChildAt(i), depth + 1)
            }
        }
    }

    private fun isTargetComponent(name: String): Boolean {
        return name.contains("Volume", ignoreCase = true) ||
                name.contains("SystemUIDialog", ignoreCase = true) ||
                name.contains("QS", ignoreCase = true) ||
                name.contains("Detail", ignoreCase = true) ||
                name.contains("Menu", ignoreCase = true) ||
                name.contains("Media", ignoreCase = true) ||
                name.contains("Output", ignoreCase = true) ||
                name.contains("Popup", ignoreCase = true)
    }

    private fun applyPanelOnlyBlur(window: Window, radiusPx: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setFormat(PixelFormat.TRANSLUCENT)
            window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = 0 }
            window.setBackgroundBlurRadius(radiusPx)
        }
    }

    private fun makeContainerTranslucent(view: View, alpha: Int, depth: Int) {
        if (depth > 8 || view is SeekBar) return

        view.backgroundTintList?.let { tintList ->
            val defaultColor = tintList.defaultColor
            if (Color.alpha(defaultColor) > alpha) {
                view.backgroundTintList = ColorStateList.valueOf(
                    Color.argb(
                        alpha,
                        Color.red(defaultColor),
                        Color.green(defaultColor),
                        Color.blue(defaultColor)
                    )
                )
            }
        }

        view.background?.let { bg ->
            val mutated = bg.mutate()
            applyAlphaToDrawable(mutated, alpha)
            view.background = mutated
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                makeContainerTranslucent(view.getChildAt(i), alpha, depth + 1)
            }
        }
    }

    private fun applyAlphaToDrawable(drawable: Drawable, alpha: Int) {
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
                    if (Color.alpha(c) > alpha) {
                        drawable.color = ColorStateList.valueOf(
                            Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
                        )
                    }
                }
            }
            is ColorDrawable -> {
                val c = drawable.color
                if (Color.alpha(c) > alpha) {
                    drawable.color = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
                }
            }
            else -> {
                drawable.alpha = alpha
            }
        }
    }
}
