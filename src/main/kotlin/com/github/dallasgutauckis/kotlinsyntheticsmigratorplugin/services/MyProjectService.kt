package com.github.dallasgutauckis.kotlinsyntheticsmigratorplugin.services

import com.intellij.openapi.project.Project
import com.github.dallasgutauckis.kotlinsyntheticsmigratorplugin.MyBundle

class MyProjectService(project: Project) {

    init {
        println(MyBundle.message("projectService", project.name))
    }
}
