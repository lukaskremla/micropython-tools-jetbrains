/*
* Copyright © 2025 Lukas Kremla / MicroPython Tools
*
* This file is part of MicroPython Tools Pro and is distributed
* under the terms of the End-User License Agreement (EULA).
*
* You may not copy, modify, distribute, or sublicense this file
* except as expressly permitted by the EULA.
*
* A copy of the EULA should have been provided with this software.
* If not, it is available online at:
* https://github.com/lukaskremla/micropython-tools-jetbrains/blob/main/EULA.txt
*
* Unauthorized use or distribution of this file is strictly prohibited.
*/

package dev.micropythontools.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.LayeredIcon
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import dev.micropythontools.freemium.MpyProServiceInterface
import dev.micropythontools.i18n.MpyBundle
import dev.micropythontools.icons.MpyIcons
import javax.swing.Icon

internal class MpyRunConfMpyCrossFactory(type: MpyRunConfType) : ConfigurationFactory(type) {
    override fun createTemplateConfiguration(project: Project): RunConfiguration {
        try {
            return project.service<MpyProServiceInterface>()
                .getMpyCrossRunConfiguration(
                    project,
                    this,
                    MpyBundle.message("run.conf.compile.name")
                )
        } catch (e: Throwable) {
            Notifications.Bus.notify(
                Notification(
                    MpyBundle.message("notification.group.name"),
                    MpyBundle.message("pro.service.error.community.artifact.notification.title"),
                    MpyBundle.message("pro.service.error.community.artifact.notification.message"),
                    NotificationType.ERROR
                )
            )

            throw e
        }
    }

    override fun getName(): String {
        return MpyBundle.message("run.conf.compile.name")
    }

    override fun getId(): String {
        return "micropython-tools-mpy-cross-configuration"
    }

    override fun getIcon(): Icon {
        val base: Icon = MpyIcons.plugin
        val pro: Icon = MpyIcons.proBadge

        val scaledBadge = IconUtil.scale(pro, null, 0.45f)

        val layered = LayeredIcon(2)
        layered.setIcon(base, 0, 0, 0)

        val pad = JBUI.scale(-3)
        val x = (base.iconWidth - scaledBadge.iconWidth - pad).coerceAtLeast(0)
        val y = (base.iconHeight - scaledBadge.iconHeight - pad).coerceAtLeast(0)
        layered.setIcon(scaledBadge, 1, x, y)

        return layered
    }

    override fun getOptionsClass() = MpyRunConfMpyCrossOptions::class.java
}