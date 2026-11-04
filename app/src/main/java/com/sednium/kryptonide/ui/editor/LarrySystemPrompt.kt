package com.sednium.kryptonide.ui.editor

import com.sednium.kryptonide.agent.oorty.SystemPrompt

object LarrySystemPrompt {
    val TEXT: String
        get() = SystemPrompt.buildImmutablePrompt()
}
