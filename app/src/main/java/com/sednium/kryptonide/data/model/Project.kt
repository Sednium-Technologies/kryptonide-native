package com.sednium.kryptonide.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Project(
    val id: String,
    val name: String,
    val template: ProjectTemplate,
    val updatedAt: Long = System.currentTimeMillis(),
    val githubRepo: String? = null,
    val path: String = ""   // on-disk root path; used by BuildEngine
)

@Serializable
enum class ProjectTemplate {
    ANDROID_JAVA, ANDROID_KOTLIN, ANDROID_COMPOSE,
    VANILLA, REACT, REACT_VITE, NEXTJS,
    FASTAPI, NODE_EXPRESS, PHP, PYTHON, RUST, CPP, C, CSHARP,
    JAVA, KOTLIN, GO, MARKDOWN, JSON, BLANK, HTML, COMPOSE, VITE, NODE
}
