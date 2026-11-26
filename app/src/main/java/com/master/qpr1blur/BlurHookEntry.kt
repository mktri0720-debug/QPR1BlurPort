package com.master.qpr1blur

import android.app.Dialog
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class BlurHookEntry : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "[QPR1BlurPort]"
        
        // 模糊半径（像素）：推荐 60 ~ 120，数值越大越朦胧
        private const val BLUR_RADIUS_PX = 85
        
        // 背景遮罩不透明度（0~255）：原版为 255（纯实心），推荐 165 ~ 195 之间兼顾可读性与通透感
        private const val PANEL_BG_ALPHA = 175
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return

        XposedBridge.log("$TAG 已注入 SystemUI，开始挂载 QPR1 模糊特性...")

        hookVolumeDialog(lpparam.classLoader)
        hookSystemUIDialog(lpparam.classLoader)
    }

    /**
     * 1. 适配音量滑块与展开面板 (VolumeDialogImpl)
     */
    private fun hookVolumeDialog(classLoader: ClassLoader) {
        val volumeDialogClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.volume.VolumeDialogImpl",
            classLoader
        )
        if (volumeDialogClass == null) {
            XposedBridge.log("$TAG 未找到 VolumeDialogImpl，跳过")
            return
        }

        // Hook initDialog 方法，在对话框初始化完成时修改 Window 与视图背景
        XposedBridge.hookAllMethods(volumeDialogClass, "initDialog", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val instance = param.thisObject
                    val dialog = XposedHelpers.getObjectField(instance, "mDialog") as? Dialog
                    val window = dialog?.window ?: return

                    applyWindowBlur(window, BLUR_RADIUS_PX)

                    // 获取音量面板主容器视图并调低背景不透明度
                    val dialogView = XposedHelpers.getObjectField(instance, "mDialogView") as? View
                    dialogView?.let { makeViewBackgroundTranslucent(it, PANEL_BG_ALPHA) }

                    // 如果存在独立的行容器 (mDialogRowsView)，也一并处理
                    val rowsView = XposedHelpers.getObjectField(instance, "mDialogRowsView") as? View
                    rowsView?.let { makeViewBackgroundTranslucent(it, PANEL_BG_ALPHA) }

                    XposedBridge.log("$TAG VolumeDialogImpl 高斯模糊注入成功！")
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG VolumeDialogImpl 注入异常: ${t.message}")
                }
            }
        })
    }

    /**
     * 2. 适配快捷设置二级菜单与系统弹窗 (SystemUIDialog)
     */
    private fun hookSystemUIDialog(classLoader: ClassLoader) {
        val sysUiDialogClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.phone.SystemUIDialog",
            classLoader
        )
        if (sysUiDialogClass == null) {
            XposedBridge.log("$TAG 未找到 SystemUIDialog，跳过")
            return
        }

        // 在所有 SystemUIDialog 准备显示 (onStart) 时挂载模糊
        XposedBridge.hookAllMethods(sysUiDialogClass, "onStart", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val dialog = param.thisObject as? Dialog ?: return
                    val window = dialog.window ?: return

                    applyWindowBlur(window, BLUR_RADIUS_PX)

                    // 调整 DecorView 的背景透明度
                    window.decorView.let { decor ->
                        makeViewBackgroundTranslucent(decor, PANEL_BG_ALPHA)
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG SystemUIDialog 注入异常: ${t.message}")
                }
            }
        })
    }

    /**
     * 核心工具：为 Window 启用原生硬件背景模糊
     */
    private fun applyWindowBlur(window: Window, radiusPx: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 确保窗口支持半透明像素格式，否则底层渲染器会忽略 Alpha 通道
            window.setFormat(PixelFormat.TRANSLUCENT)
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            
            // 设置窗口背景高斯模糊半径（仅模糊窗口自身轮廓内的背景）
            window.setBackgroundBlurRadius(radiusPx)
        }
    }

    /**
     * 核心工具：递归将视图背景 Drawable 转为半透明，保留圆角和动态取色
     */
    private fun makeViewBackgroundTranslucent(view: View, alpha: Int) {
        view.background?.let { bg ->
            val mutated = bg.mutate()
            applyAlphaToDrawable(mutated, alpha)
            view.background = mutated
        }
    }

    private fun applyAlphaToDrawable(drawable: Drawable, alpha: Int) {
        when (drawable) {
            is LayerDrawable -> {
                for (i in 0 until drawable.numberOfLayers) {
                    applyAlphaToDrawable(drawable.getDrawable(i), alpha)
                }
            }
            is GradientDrawable, is ColorDrawable -> {
                drawable.alpha = alpha
            }
            else -> {
                drawable.alpha = alpha
            }
        }
    }
}
