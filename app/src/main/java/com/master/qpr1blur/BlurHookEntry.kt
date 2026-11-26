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
import android.view.Window
import android.view.WindowManager
import android.widget.PopupWindow
import android.widget.SeekBar
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class BlurHookEntry : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "[QPR1BlurPort]"

        // 动态同步的系统原生模糊半径（初始兜底值 150px，注入后会实时更新为系统真实值）
        @Volatile
        private var nativeSystemBlurRadiusPx = 150

        // 校准后的原生级遮罩不透明度（约 72% 浓度，兼顾动态取色饱和度与毛玻璃通透感）
        private const val PANEL_BG_ALPHA = 185
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return

        XposedBridge.log("$TAG 已注入 SystemUI，开始同步系统原生模糊参数...")

        sniffNativeBlurRadius(lpparam.classLoader)
        hookDialogs()
        hookPopupWindows()
        hookOverlayWindows(lpparam.classLoader)
        hookEmbeddedDropoutViews()
    }

    /**
     * 嗅探系统通知栏 BlurUtils 的真实最大模糊半径，确保音量条与系统级模糊程度 1:1 一致
     */
    private fun sniffNativeBlurRadius(classLoader: ClassLoader) {
        val blurUtilsClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.BlurUtils",
            classLoader
        )
        if (blurUtilsClass != null) {
            // 监听系统通知栏计算模糊半径的过程，直接截取满拉状态下的原生像素值
            XposedBridge.hookAllMethods(blurUtilsClass, "blurRadiusOfRatio", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val ratio = param.args.firstOrNull() as? Float ?: return
                    val result = param.result as? Int ?: return
                    if (ratio >= 0.95f && result > 0 && result != nativeSystemBlurRadiusPx) {
                        nativeSystemBlurRadiusPx = result
                        XposedBridge.log("$TAG 已实时同步系统原生满载模糊半径: ${nativeSystemBlurRadiusPx}px")
                    }
                }
            })

            // 监听最终提交给 SurfaceFlinger 的 applyBlur 数值
            XposedBridge.hookAllMethods(blurUtilsClass, "applyBlur", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val radius = param.args.getOrNull(1) as? Int ?: return
                    if (radius > 60 && radius > nativeSystemBlurRadiusPx) {
                        nativeSystemBlurRadiusPx = radius
                        XposedBridge.log("$TAG 从 applyBlur 捕获系统最大模糊半径: ${nativeSystemBlurRadiusPx}px")
                    }
                }
            })
        }
    }

    /**
     * 从 Context 资源表中读取系统内置的最大窗口模糊半径作为初始基准
     */
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
                    if (px > 0 && px != nativeSystemBlurRadiusPx) {
                        nativeSystemBlurRadiusPx = px
                        XposedBridge.log("$TAG 从系统资源 ($name) 读取原生模糊半径: ${px}px")
                        break
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun hookDialogs() {
        XposedBridge.hookAllMethods(Dialog::class.java, "onStart", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val dialog = param.thisObject as? Dialog ?: return
                    val className = dialog.javaClass.name
                    val window = dialog.window ?: return

                    updateRadiusFromContext(dialog.context)

                    if (isTargetComponent(className)) {
                        applyPanelOnlyBlur(window, nativeSystemBlurRadiusPx)
                        window.decorView.let { decor ->
                            makeContainerTranslucent(decor, PANEL_BG_ALPHA, 0)
                        }
                        XposedBridge.log("$TAG 已注入原生级模糊 (${nativeSystemBlurRadiusPx}px) 至 Dialog: $className")
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG Dialog Hook 异常: ${t.message}")
                }
            }
        })
    }

    private fun hookPopupWindows() {
        XposedBridge.hookAllMethods(PopupWindow::class.java, "showAtLocation", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                handlePopupWindow(param.thisObject as? PopupWindow)
            }
        })
        XposedBridge.hookAllMethods(PopupWindow::class.java, "showAsDropDown", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                handlePopupWindow(param.thisObject as? PopupWindow)
            }
        })
    }

    private fun handlePopupWindow(popup: PopupWindow?) {
        if (popup == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val contentView = popup.contentView ?: return
            updateRadiusFromContext(contentView.context)
            val rootView = contentView.rootView ?: return
            makeContainerTranslucent(contentView, PANEL_BG_ALPHA, 0)

            val viewRootImpl = XposedHelpers.callMethod(rootView, "getViewRootImpl")
            if (viewRootImpl != null) {
                XposedHelpers.callMethod(viewRootImpl, "setBackgroundBlurRadius", nativeSystemBlurRadiusPx)
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG PopupWindow Hook 异常: ${t.message}")
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

                        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: View) {
                                try {
                                    val viewRootImpl = XposedHelpers.callMethod(v, "getViewRootImpl")
                                    if (viewRootImpl != null) {
                                        XposedHelpers.callMethod(
                                            viewRootImpl,
                                            "setBackgroundBlurRadius",
                                            nativeSystemBlurRadiusPx
                                        )
                                    }
                                    makeContainerTranslucent(v, PANEL_BG_ALPHA, 0)
                                    v.post { makeContainerTranslucent(v, PANEL_BG_ALPHA, 0) }
                                    XposedBridge.log("$TAG 悬浮面板已应用原生级模糊 (${nativeSystemBlurRadiusPx}px): $title")
                                } catch (e: Throwable) {
                                    XposedBridge.log("$TAG 悬浮窗口模糊异常: ${e.message}")
                                }
                            }

                            override fun onViewDetachedFromWindow(v: View) {}
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
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val child = param.args.firstOrNull() as? View ?: return
                    val name = child.javaClass.name
                    if (name.contains("QSDetail", ignoreCase = true) ||
                        name.contains("Dropdown", ignoreCase = true) ||
                        name.contains("PopupMenu", ignoreCase = true)
                    ) {
                        makeContainerTranslucent(child, PANEL_BG_ALPHA, 0)
                    }
                } catch (_: Throwable) {
                }
            }
        })
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
            val translucentColor = Color.argb(
                alpha,
                Color.red(defaultColor),
                Color.green(defaultColor),
                Color.blue(defaultColor)
            )
            view.backgroundTintList = ColorStateList.valueOf(translucentColor)
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
            }
        }
    }
}
