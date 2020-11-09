package com.github.dallasgutauckis.kotlinsyntheticsmigrator.services

import com.intellij.openapi.project.Project
import com.github.dallasgutauckis.kotlinsyntheticsmigrator.MyBundle

class MyProjectService(project: Project) {

    init {
        println(MyBundle.message("projectService", project.name))
    }
}
